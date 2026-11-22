# 업로드 완료 Controller의 CompletableFuture 응답 (#82)

## 대상과 응답

`POST /api/v1/uploads/{uploadId}/complete`에 적용한다.
`DocumentUploadCompletionController.completeUpload()`는
`CompletableFuture<ResponseEntity<CompleteUploadResponse>>`를 반환한다.

정상 응답은 기존과 같은 `200 OK`, `CompleteUploadResponse`, `Cache-Control: no-store`다.
작업 접수만 반환하는 API가 아니며, 완료 처리가 끝날 때까지 HTTP 응답은 열린 상태로 대기한다.
PDF 변환은 기존 DocumentConversionJob 작업자가 처리한다.

## 실행 흐름

1. 기존 Security 필터와 경로 ID 검증을 적용한다.
2. Controller의 요청 스레드에서 OidcUser의 issuer·subject를 문자열로 추출한다.
3. DocumentUploadCompletionAsyncService가 전용 실행기에 기존 DocumentUploadCompletionService 호출을 제출한다.
4. Controller는 결과를 get()·join()으로 기다리지 않고 CompletableFuture를 반환한다.
5. 전용 스레드에서 기존 prepare() → S3UploadVerifier.verify() → complete()를 실행한다.
6. Future가 완료되면 Spring MVC가 응답 처리를 재개한다. 업무 예외는 기존 HTTP 상태로 처리한다.

HttpServletRequest·OidcUser·JPA 엔티티를 작업 인자로 전달하지 않는다.
작업은 명시적으로 전달받은 uploadId·issuer·subject로 기존 권한 검사를 수행한다.
요청 스레드의 SecurityContext나 트랜잭션이 자동 전달된다고 가정하지 않는다.

기존 DocumentUploadCompletionService의 NOT_SUPPORTED와 별도 Spring 객체인
DocumentUploadCompletionTransactions의 prepare()·complete() 트랜잭션을 유지한다.
S3 조회 중에는 DB 트랜잭션을 유지하지 않는다.

## 전용 실행기

UploadCompletionAsyncConfig의 uploadCompletionTaskExecutor를 Qualifier로 지정한다.
스레드 이름은 `upload-completion-`으로 시작한다. `defaultCandidate=false`로 선언하여
다른 곳의 일반적인 Executor 주입이나 공용 실행기를 대체하지 않도록 한다.

고정된 최대 스레드 수와 유한 대기열을 사용한다. 포화 시 호출자 스레드에서 업무를 실행하지 않고
503을 반환한다. 애플리케이션 컨텍스트 종료 시 Spring이 실행기를 종료한다.

| 설정 | 환경변수 | 기본값 |
| --- | --- | --- |
| document.upload-completion.async.threads | UPLOAD_COMPLETION_ASYNC_THREADS | 4 |
| document.upload-completion.async.queue-capacity | UPLOAD_COMPLETION_ASYNC_QUEUE_CAPACITY | 8 |
| document.upload-completion.async.response-timeout | UPLOAD_COMPLETION_ASYNC_RESPONSE_TIMEOUT | 30s |
| aws.s3.head-api-call-timeout | S3_HEAD_API_CALL_TIMEOUT | 10s |
| aws.s3.head-api-call-attempt-timeout | S3_HEAD_API_CALL_ATTEMPT_TIMEOUT | 3s |

스레드는 1개 이상, 대기열은 0개 이상, Future 응답 제한은 1ms 이상이어야 한다.
대기열 0은 작업을 대기열에 보관하지 않는 설정이다.
HeadObject의 시간 제한은 양수이며 전체 제한은 단일 시도 제한 이상이어야 한다.

## 시간 초과와 예외

- Future의 응답 제한은 대기열 대기와 업무 실행을 포함한다. 기본값은 30초다.
- 이 Controller에만 Servlet 비동기 제한을 Future 제한 + 5초로 설정한다. 기본값은 35초다.
- Future 제한이 먼저 발생하면 504를 반환한다. Servlet 제한이 먼저 발생하는 경우에도 이 Controller의 인터셉터가 504로 처리한다.
- 다른 Controller의 Servlet 비동기 제한은 변경하지 않는다.
- 기존 403·404·409·410·502·503 등의 업무 예외는 CompletionException을 중첩하지 않고 전달하여 상태를 유지한다.
- 실행기가 포화되면 업무를 시작하지 않고 503으로 응답한다.

S3ObjectReader.head()의 요청별 overrideConfiguration에 전체 호출 10초·단일 시도 3초를 설정한다.
전체 호출 제한은 SDK 재시도를 포함한다. DOCX 다운로드·PDF 업로드에는 이번 HeadObject 설정을 적용하지 않는다.
SDK 시간 제한은 정확한 시각의 작업 중단을 보장하는 장치로 취급하지 않는다.

**504는 실제 업무가 취소되었거나 롤백되었다는 뜻이 아니다.** 이미 실행 중인 S3 확인과 DB 완료 처리는
응답 제한 이후에도 끝날 수 있다. 같은 uploadId로 완료를 다시 요청하면 기존 잠금·상태 재확인으로
동일한 DocumentVersion을 반환한다. 원래 요청이 늦게 돌아와도 새 버전을 중복 등록하지 않는다.
대기열에서 Future 제한이 지난 작업은 이후 실행 시 업무 함수를 호출하지 않음을 Java 17 테스트로 확인했다.

비동기 반환형은 S3 조회나 PDF 변환 자체를 빠르게 만드는 기능이 아니다.
동기 S3 조회를 전용 스레드로 옮겨 Servlet 요청 스레드를 다른 요청에 사용할 수 있게 한다.
S3AsyncClient로의 변경이나 PDF 변환 작업자의 구조 변경은 포함하지 않는다.

## 검증 결과

2026-11-22, 개발용 ftfm과 분리된 임시 MySQL DB에서 관련 테스트 138개가 통과했다.
종료 후 임시 DB·계정을 정리했다.

| 테스트 클래스 | 통과 |
| --- | ---: |
| UploadCompletionAsyncConfigTest | 10 |
| DocumentUploadCompletionAsyncServiceTest | 10 |
| DocumentUploadCompletionServiceTest | 8 |
| S3ObjectReaderTest | 5 |
| S3ObjectReaderDownloadTest | 10 |
| S3UploadVerifierTest | 9 |
| DocumentConversionFlowTest | 1 |
| DocumentUploadCompletionApiTest | 15 |
| DocumentUploadCompletionAsyncApiTest | 4 |
| DocumentUploadCompletionIntegrationTest | 21 |
| DocumentVersionCompletionIntegrationTest | 15 |
| DocumentVersionReviewApiTest | 30 |

- 전용 스레드·유한 대기열·포화 거부·실행기 종료 및 잘못된 설정 거부
- Controller의 asyncStarted·asyncDispatch, 지연 중 미완료 응답과 기존 JSON·캐시 정책
- 대기 중인 서로 다른 계정의 issuer·subject 분리와 포화 요청 503
- Future 시간 초과와 Servlet 비동기 시간 초과의 504 응답
- 다른 Controller의 비동기 제한 유지와 기존 인증·CSRF·ID·업무 오류 상태 유지
- 실제 DB에서 S3 검증 중 트랜잭션이 없고, 완료 결과가 커밋되는지 확인
- 504 이후 재요청이 먼저 완료되고 원래 요청이 늦게 끝나도 문서·버전·변환 작업 한 개 유지
- 기존 새 버전 등록·동시 완료·롤백 및 #80 완료 기록 API 회귀

외부 S3와 변환 서버는 모의 객체를 사용했다. 실제 AWS의 지연·SDK 시간 초과 동작이나
실제 Servlet 컨테이너의 타이머·성능 부하 시험을 검증한 결과는 아니다.
Servlet 타임아웃 테스트는 MockAsyncContext의 타임아웃 이벤트를 발생시켜 응답 처리를 확인했다.

## 참고

- [Spring MVC 비동기 요청 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
- [AWS SDK 요청별 시간 제한](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)
