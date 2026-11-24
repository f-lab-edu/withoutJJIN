# 업로드 완료의 CompletableFuture 응답과 비동기 S3 조회 (#82·#85)

## 대상과 응답

`POST /api/v1/uploads/{uploadId}/complete`에 적용한다.
`DocumentUploadCompletionController.completeUpload()`는
`CompletableFuture<ResponseEntity<CompleteUploadResponse>>`를 반환한다.
정상 응답은 `200 OK`, `CompleteUploadResponse`, `Cache-Control: no-store`다.

HTTP 응답은 업로드 완료 처리 결과가 나올 때까지 열린 상태로 대기한다.
`DocumentConversionJob`의 PDF 변환 완료까지 기다리는 API는 아니다.

#82에서는 동기 `DocumentUploadCompletionService.completeUpload()` 호출 전체를 전용 실행기에 제출했다.
#85에서는 `DocumentUploadCompletionAsyncService`가 `DocumentUploadCompletionTransactions`와
`S3AsyncUploadVerifier`를 직접 연결한다. S3 응답을 기다리는 동안 전용 작업 스레드도 반환한다.

## 실행 흐름

1. Security 필터에서 로그인·CSRF를 검사하고, Controller에서 경로 ID를 검증한다.
2. Controller가 요청 스레드에서 `OidcUser`의 `issuer`·`subject`를 문자열로 추출한다.
3. `DocumentUploadCompletionAsyncService.completeUpload()`가 진행 중 요청 한도를 확보하고,
   `uploadCompletionTaskExecutor`에 `prepareAndVerify()`를 제출한다. 한도를 확보하지 못하면 503이다.
4. `upload-completion-*` 스레드가 `DocumentUploadCompletionTransactions.prepare()`를 호출한다.
   읽기 전용 트랜잭션에서 요청자·소속·`DocumentUpload` 상태를 확인하고 `PreparedUpload`를 반환한다.
   이미 `COMPLETED`이면 기존 `CompleteUploadResponse`를 반환하고 S3 조회와 최종 저장을 생략한다.
5. `S3AsyncUploadVerifier.verify()` → `S3AsyncObjectReader.head()` → `S3AsyncClient.headObject()`를 호출한다.
   `S3AsyncObjectReader.head()`가 Future를 반환하면 작업 스레드는 S3 응답을 기다리지 않고 실행기로 돌아간다.
6. SDK가 Future를 완료하면 `S3AsyncUploadVerifier`가 응답의 크기·Content-Type을
   `PreparedUpload`에 담긴 `StoreFile.sizeBytes`·`StoreFile.mediaType`과 비교한다.
7. 검증에 성공하면 `DocumentUploadCompletionTransactions.complete()`를 전용 실행기에 다시 제출한다.
   `upload-completion-*` 스레드가 트랜잭션 안에서 잠금·권한·만료·상태를 재확인하고,
   `DocumentVersion` 등록, `Document.revision` 갱신, `DocumentUpload` 완료,
   `PENDING` 상태의 `DocumentConversionJob` 저장을 함께 커밋한다.
8. 결과 Future가 완료되면 Spring MVC가 비동기 dispatch로 요청 처리를 재개하고 HTTP 응답을 마무리한다.
   응답을 마무리하는 Tomcat 스레드는 최초 요청 스레드와 다를 수 있다.

Controller와 비동기 완료 경로는 `get()`·`join()`으로 결과를 기다리지 않는다.
`HttpServletRequest`·`OidcUser`·JPA 엔티티를 작업 인자로 전달하지 않으며,
권한 검사는 명시적으로 전달한 `uploadId`·`issuer`·`subject`로 수행한다.
요청 스레드의 `SecurityContext`나 트랜잭션이 다른 스레드로 자동 전달된다고 가정하지 않는다.

## 스레드와 트랜잭션

| 단계 | 실행 위치 | DB 트랜잭션 |
| --- | --- | --- |
| 로그인 정보 추출·작업 제출 | Tomcat 요청 스레드 | 완료 처리용 트랜잭션 없음 |
| `prepare()` | `upload-completion-*` | 읽기 전용 |
| S3 요청 시작 | `upload-completion-*` | 없음 |
| S3 응답 대기 | SDK의 비동기 I/O | 없음 |
| 응답 검증·후속 작업 제출 | S3 Future를 완료하는 스레드 | 없음 |
| `complete()` | `upload-completion-*` | 쓰기·행 잠금 |
| JSON 응답 마무리 | 비동기 dispatch를 처리하는 Tomcat 스레드 | 완료 처리용 트랜잭션 없음 |

`prepare()`·`complete()`는 별도 Spring 객체의 메서드이므로 각각의 `@Transactional`이 적용된다.
S3 응답 대기 중에는 DB 트랜잭션이나 행 잠금을 유지하지 않는다.
JPA 처리는 SDK Future의 콜백에서 직접 실행하지 않고 전용 실행기에 제출한다.
두 DB 단계가 같은 작업 스레드에서 실행된다는 보장은 없다.

SDK의 인증정보 조회·서명 등 일부 처리는 호출 스레드에서 지연될 수 있으므로,
S3 요청을 시작하는 코드도 전용 실행기에서 실행한다.
`S3AsyncClient`를 사용해도 JPA·JDBC 처리가 비동기 I/O로 바뀌는 것은 아니다.

## 실행기와 진행 중 요청 한도

`UploadCompletionAsyncConfig.uploadCompletionTaskExecutor()`를 `@Qualifier`로 지정한다.
고정된 스레드 수·유한 대기열·`AbortPolicy`를 사용하며, 포화 작업을 호출자 스레드에서 실행하지 않는다.
`defaultCandidate=false`이므로 일반적인 `Executor` 주입을 대체하지 않는다.

`max-in-flight`는 `DocumentUploadCompletionAsyncService` 인스턴스의 `Semaphore`로 관리한다.
실행기 대기·DB 처리·S3 응답 대기를 포함한 진행 중 업무의 개수를 제한한다.
스레드를 반환한 S3 대기 요청도 한도를 점유한다. 여러 애플리케이션 인스턴스가 공유하는 전역 한도는 아니다.

| 설정 | 환경변수 | 기본값 |
| --- | --- | --- |
| `document.upload-completion.async.threads` | `UPLOAD_COMPLETION_ASYNC_THREADS` | 4 |
| `document.upload-completion.async.queue-capacity` | `UPLOAD_COMPLETION_ASYNC_QUEUE_CAPACITY` | 8 |
| `document.upload-completion.async.response-timeout` | `UPLOAD_COMPLETION_ASYNC_RESPONSE_TIMEOUT` | 30s |
| `document.upload-completion.async.max-in-flight` | `UPLOAD_COMPLETION_ASYNC_MAX_IN_FLIGHT` | 12 |

스레드·진행 중 요청 한도는 1 이상, 대기열은 0 이상, HTTP Future 응답 제한은 1ms 이상이어야 한다.
대기열 0은 실행 가능한 스레드가 없을 때 작업을 대기열에 보관하지 않는 설정이다.
`max-in-flight` 이내여도 실행기 대기열이 포화되면 작업 제출이 거부되어 503이 될 수 있다.

## S3 비동기 클라이언트 설정

`S3AsyncHeadConfig.s3HeadAsyncClient()`가 `NettyNioAsyncHttpClient`를 사용하는 `S3AsyncClient`를 생성한다.
`netty-nio-client`를 직접 설정하기 위해 Gradle의 `implementation` 의존성으로 선언했다.
Spring 컨텍스트가 종료되면 `S3AsyncClient.close()`가 호출되고,
SDK가 소유하는 HTTP 클라이언트와 I/O 스레드도 정리된다.

| 설정 | 환경변수 | 기본값 |
| --- | --- | --- |
| `aws.s3.async-head.max-concurrency` | `S3_ASYNC_HEAD_MAX_CONCURRENCY` | 12 |
| `aws.s3.async-head.max-pending-connection-acquires` | `S3_ASYNC_HEAD_MAX_PENDING_CONNECTION_ACQUIRES` | 12 |
| `aws.s3.async-head.event-loop-threads` | `S3_ASYNC_HEAD_EVENT_LOOP_THREADS` | 2 |
| `aws.s3.async-head.connection-timeout` | `S3_ASYNC_HEAD_CONNECTION_TIMEOUT` | 2s |
| `aws.s3.async-head.connection-acquisition-timeout` | `S3_ASYNC_HEAD_CONNECTION_ACQUISITION_TIMEOUT` | 1s |
| `aws.s3.head-api-call-timeout` | `S3_HEAD_API_CALL_TIMEOUT` | 10s |
| `aws.s3.head-api-call-attempt-timeout` | `S3_HEAD_API_CALL_ATTEMPT_TIMEOUT` | 3s |

`max-concurrency`는 Netty HTTP 클라이언트의 동시 요청 수,
`max-pending-connection-acquires`는 HTTP 연결 확보를 기다리는 요청 수를 제한한다.
두 설정과 I/O 스레드 수는 1 이상, 연결·연결 확보 대기시간은 1ms 이상이어야 한다.
I/O 스레드 기본값 2는 SDK 전체 스레드 수나 애플리케이션 전체 스레드 수를 뜻하지 않는다.

`S3AsyncObjectReader.head()`는 요청별 `overrideConfiguration`으로 전체 호출·단일 시도 제한을 적용한다.
전체 호출 제한은 SDK 재시도와 재시도 사이 대기를 포함한다.
두 제한은 양수이며 전체 제한은 단일 시도 제한 이상이어야 한다.
설정값은 애플리케이션을 재시작하여 반영한다.

## 시간 초과·실패·재요청

| 상황 | HTTP 상태 |
| --- | ---: |
| 진행 중 요청 한도 초과·실행기 작업 제출 거부 | 503 |
| S3가 파일 없음(404)으로 응답 | 404 |
| S3가 403·500 등 다른 오류로 응답 | 502 |
| SDK 연결 실패·SDK 호출 시간 제한 등 `SdkClientException` | 503 |
| S3 파일 크기·Content-Type과 `StoreFile` 불일치 | 409 |
| HTTP Future 응답 제한 초과 | 504 |

`prepare()`·`complete()`의 권한·존재·상태·만료 오류도 기존 HTTP 상태로 전달한다.
S3 호출 제한(기본 10초)에 따른 503과 HTTP 응답 제한(기본 30초)에 따른 504는 서로 다르다.
HTTP Future 응답 제한은 실행기 대기·DB 처리·S3 조회를 포함한다.
이 Controller의 Servlet 비동기 제한은 Future 제한 + 5초(기본 35초)이며,
Servlet 제한이 먼저 발생하는 경우에도 인터셉터가 504로 처리한다.
다른 Controller의 Servlet 비동기 제한은 변경하지 않는다.

**504는 DB 롤백이나 S3 작업 취소를 보장하지 않는다.**
HTTP 응답 Future와 실제 업무 Future를 분리하므로, HTTP 응답만 시간 초과되어도
진행 중 요청 한도는 실제 업무 Future가 끝나야 반환한다.
서비스가 반환한 Future를 직접 취소하는 경우에도 실행 중인 S3 Future가 자동 취소되지는 않는다.

실행기에서 `prepare()`를 시작하기 전·후, S3 검증 후와 `complete()` 실행 직전에
HTTP 응답 Future가 종료되었는지 확인한다. 이미 종료된 것으로 확인되면 다음 단계를 시작하지 않는다.
따라서 504 뒤 늦게 도착한 S3 성공 응답으로 최종 저장을 새로 시작하지 않는다.
다만 이미 시작한 DB 트랜잭션은 응답 제한 이후에도 커밋될 수 있다.

브라우저는 같은 `uploadId`로 완료 API를 재요청할 수 있다.
진행 중 요청 한도가 계속 점유되어 있으면 재요청도 503이 될 수 있다.
재요청이 접수되면 `complete()`의 잠금·상태 재확인으로 이미 등록된 `DocumentVersion`을 반환한다.
원래 요청과 재요청이 겹치더라도 `DocumentVersion`·`DocumentConversionJob`을 중복 등록하지 않는다.

## 검증 결과와 범위

2026-11-23, Java 17에서 아래 177개 테스트가 통과했다.
DB 통합 테스트는 개발용 `ftfm`과 분리된 임시 MySQL DB·계정을 사용하고 종료 후 정리했다.

| 테스트 클래스 | 통과 |
| --- | ---: |
| `UploadCompletionAsyncConfigTest` | 12 |
| `S3AsyncHeadConfigTest` | 10 |
| `S3AsyncObjectReaderTest` | 6 |
| `S3AsyncUploadVerifierTest` | 10 |
| `S3AsyncObjectReaderHttpTest` | 1 |
| `DocumentUploadCompletionAsyncServiceTest` | 20 |
| `DocumentUploadCompletionServiceTest` | 8 |
| `S3ObjectReaderTest` | 5 |
| `S3ObjectReaderDownloadTest` | 10 |
| `S3UploadVerifierTest` | 9 |
| `DocumentConversionFlowTest` | 1 |
| `DocumentUploadCompletionApiTest` | 15 |
| `DocumentUploadCompletionAsyncApiTest` | 4 |
| `DocumentUploadCompletionIntegrationTest` | 21 |
| `DocumentVersionCompletionIntegrationTest` | 15 |
| `DocumentVersionReviewApiTest` | 30 |

- 단일 작업 스레드가 S3 응답 대기 중 다른 작업을 실행하고, S3 응답 후 DB 작업은 전용 실행기에 제출되는지 확인했다.
- 이미 완료된 `DocumentUpload`가 S3 재조회 없이 기존 결과를 반환하는지 확인했다.
- 요청 한도·실행기 포화 503, HTTP Future·Servlet 시간 초과 504, S3 오류·메타데이터 불일치의 상태 매핑을 확인했다.
- 시간 초과·서비스 Future 취소 이후에도 S3 Future 종료 전에는 한도를 반환하지 않고, 늦은 S3 성공으로 저장을 시작하지 않는지 확인했다.
- 실제 MySQL에서 트랜잭션 밖의 S3 검증, 완료 커밋·롤백, 504 뒤 같은 비동기 서비스로 재요청했을 때 문서·버전·변환 작업 한 개 유지를 확인했다.
- 기존 로그인·CSRF·ID 검증, 새 버전 등록·동시 완료와 `DocumentVersionReview` API를 함께 검증했다.

`S3AsyncObjectReaderHttpTest`는 실제 `S3AsyncClient`·Netty와 로컬 HTTP 서버를 사용했다.
HEAD 응답을 지연시켜 호출 스레드가 반환되는지 확인했으며, AWS에는 접속하지 않았다.
서비스·DB 통합 테스트의 S3 검증과 변환 흐름의 외부 I/O는 모의 객체를 사용했다.
실제 AWS·Gotenberg 연동, 실제 Tomcat의 타이머 동작과 부하 성능을 검증한 결과는 아니다.
Servlet 시간 초과 테스트는 `MockAsyncContext`의 타임아웃 이벤트로 응답 처리를 확인했다.

PDF 변환의 DOCX 다운로드·PDF 저장, S3 삭제, 초대 메일 발송의 실행 구조는 이번 비동기 HeadObject 경로와 별개다.

## 참고

- [Spring MVC 비동기 요청 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [AWS SDK 비동기 클라이언트](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/asynchronous.html)
- [AWS SDK Netty HTTP 클라이언트 설정](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-netty.html)
- [AWS SDK 요청별 시간 제한](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)
