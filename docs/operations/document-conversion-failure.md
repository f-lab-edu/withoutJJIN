# DocumentConversionJob 변환 실패 기록

## 저장하는 정보

`DocumentConversionJob`은 가장 최근 실행의 실패 정보만 저장한다.
실패 이력을 별도 행으로 누적하지 않는다.

| 필드 | 의미 |
| --- | --- |
| failureStage | 실패한 처리 단계 |
| failureCode | 예외 종류·상태 또는 변환기에서 명시한 고정 원인 코드 |
| failedAt | 실패 상태를 기록한 시각. 기존 엔티티와 동일하게 Asia/Seoul 기준 |

기존 행과의 호환성을 위해 세 컬럼은 NULL을 허용한다. 기존 FAILED 행에 원인을
추측하여 채우지 않는다. 로컬의 기존 `spring.jpa.hibernate.ddl-auto: update` 설정에서는
Spring을 다시 실행할 때 컬럼이 추가된다. 이번 검증은 분리된 임시 DB에서 수행했으며
개발용 `ftfm` DB에는 DDL을 직접 실행하지 않았다.

## 실패 단계

| failureStage | 처리 위치 |
| --- | --- |
| ORIGINAL_DOWNLOAD | S3ObjectReader.downloadDocx |
| PDF_CONVERSION | WordPdfConverter.convert. PDF 응답 검증 포함 |
| PDF_UPLOAD | S3PdfWriter.upload |
| RESULT_SAVE | DocumentConversionTransactions.complete. 원본 연결 재검증과 DB 커밋 포함 |
| UNKNOWN | 단계 정보가 없는 기존 호출 또는 예상하지 못한 예외 경로 |

`DocumentConversionService`가 각 외부 작업의 RuntimeException을
`DocumentConversionException`으로 감싸 단계와 코드를 전달한다. 원본 예외는 cause로
유지하고 예외 메시지에는 단계·코드만 넣는다. `DocumentConversionWorker`는
결과 DB 저장에서 발생한 예외를 RESULT_SAVE로 구분한다.

## 원인 코드

| failureCode | 판별 조건 |
| --- | --- |
| ACCESS_DENIED | S3 응답 403 |
| FILE_NOT_FOUND | 원본 다운로드의 S3 응답 404. NoSuchBucket은 제외 |
| CONNECTION_FAILED | ResourceAccessException, ConnectException, SocketException, UnknownHostException |
| TIMEOUT | JDK·소켓·AWS SDK의 명시적 시간 초과 예외 또는 S3 응답 408·504 |
| CONVERSION_FAILED | WordPdfConversionException의 기본 코드. Gotenberg의 실패 HTTP 응답 포함 |
| INVALID_PDF | WordPdfConverter가 PDF Content-Type·파일 구조·페이지 수 검증에서 명시한 코드 |
| SIZE_LIMIT_EXCEEDED | WordPdfConverter가 PDF 응답의 64MiB 크기 제한 검사에서 명시한 코드 |
| RESULT_SAVE_FAILED | 변환 결과 DB 반영 단계에서 발생한 실패 |
| UNKNOWN | 위 조건으로 원인을 확정할 수 없는 예외 |

예외 메시지의 단어로 분류하지 않는다. 중첩된 cause에서 시간 초과도 확인한다.
SdkClientException이라는 이유만으로 통신 오류라고 판단하지 않는다. 예를 들어
인증 설정 문제로 발생했지만 구체적인 원인 예외가 없는 경우 UNKNOWN으로 기록한다.
알 수 없는 입력 검증 오류나 S3 500 등도 UNKNOWN일 수 있다.

이 코드는 원인 분류이며 자동 재시도 가능 여부를 뜻하지 않는다. ACCESS_DENIED도
반드시 IAM 정책 하나만의 문제라는 뜻은 아니다. 저장된 단계와 실제 환경을 함께 확인한다.

## 트랜잭션과 재시도

1. claim이 PENDING을 PROCESSING으로 변경하고 커밋한다.
2. 다운로드·변환·PDF 업로드는 DB 트랜잭션 밖에서 실행한다.
3. 성공하면 complete가 StoreFile 생성·PDF 연결·페이지 수·COMPLETED를 함께 커밋한다.
4. 실패하면 markFailed의 독립 트랜잭션에서 FAILED와 세 실패 필드를 함께 기록한다.
5. 재시도 API는 같은 DocumentConversionJob을 PENDING으로 바꾸며 세 실패 필드를 지운다.

결과 DB 저장이 롤백되어도 실패 기록은 별도로 커밋한다. 재시도 접수가 롤백되면
이전 FAILED와 실패 정보가 복구된다. markFailed는 PROCESSING만 변경하므로 완료한
작업이나 이미 기록한 실패 정보를 뒤늦은 호출이 덮어쓰지 않는다.

예외 원문·문서 내용·인증 정보·S3 응답 본문은 실패 필드에 저장하지 않는다.
FAILED를 자동 재처리하지 않으며 기존 수동 재시도 API의 권한·상태 조건은 유지한다.

## 확인 방법과 범위

```sql
SELECT id, status, failure_stage, failure_code, failed_at
FROM document_conversion_job
ORDER BY id DESC
LIMIT 10;
```

실패 정보 조회 API와 화면 표시는 이번 작업에 포함하지 않는다. 서버 강제 종료나
실패 기록 시점의 DB 장애에서는 PROCESSING에 남을 수 있으며 복구 기능은 별도 작업이다.
실패 정보 저장도 실패하면 최초 예외를 유지하고 기록 실패 예외를 suppressed로 전달한다.
PDF 업로드 후 DB 저장이 실패하면 S3에 연결되지 않은 PDF가 남을 수 있으며 이번 변경은
해당 S3 파일의 정리까지 추가하지 않는다.

## 검증

- 실제 DocumentConversionService·DocumentConversionWorker·MySQL을 연결하고 외부 작업만
  모의 처리하여 단계·원인 코드·실패 시각 저장과 원문 미저장을 확인한다.
- 결과 SQL 실행 후 실패를 주입하여 PDF 연결·페이지 수·StoreFile 롤백과 실패 기록을 확인한다.
- 호출자 롤백과 무관한 실패 기록, 뒤늦은 실패 호출의 완료 상태 보존을 확인한다.
- 재시도 성공·접수 롤백·동시 접수·기존 NULL 실패 정보의 호환성을 확인한다.

실제 S3·Gotenberg를 호출한 실패 재현은 포함하지 않는다.


### 2026-11-21 검증 결과

개발용 `ftfm`과 분리한 임시 MySQL DB에서 아래 176개 테스트가 통과했다.
검증 후 임시 DB와 테스트 전용 계정을 정리했다.

| 테스트 클래스 | 통과 |
| --- | ---: |
| DocumentConversionExceptionTest | 21 |
| WordPdfConverterTest | 24 |
| DocumentConversionServiceTest | 10 |
| DocumentConversionFlowTest | 1 |
| DocumentConversionWorkerTest | 6 |
| DocumentConversionWorkerIntegrationTest | 15 |
| DocumentConversionRetryServiceIntegrationTest | 8 |
| DocumentConversionFailureIntegrationTest | 14 |
| DocumentConversionJobTest | 29 |
| DocumentConversionJobPersistenceTest | 8 |
| DocumentConversionRetryApiTest | 40 |

`DocumentConversionFailureIntegrationTest`는 실제 변환 서비스·작업자·트랜잭션을 사용하고,
외부 파일 읽기·변환·쓰기는 모의 객체로 대체한다. 실제 S3·Gotenberg와 화면은 검증하지 않았다.
