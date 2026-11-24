# 실패한 PDF 변환 재시도 API

## 목적과 범위

PDF 변환에 실패한 문서 버전을 원본 재업로드 없이 다시 변환한다. 기존
`DocumentConversionJob`을 `FAILED`에서 `PENDING`으로 변경하며 작업·업로드·버전·파일
행을 새로 만들지 않는다. 버전 ID, 버전 번호, 문서의 revision, 원본 연결과 작업의
생성 시각은 유지한다.

사용자가 API를 호출할 때만 한 번 재시도한다. 스케줄러는 `FAILED`를 자동으로 반복
처리하지 않는다. 권한 부족·손상된 원본처럼 원인이 남아 있으면 다시 실패할 수 있으므로
원인을 확인하고 해결한 뒤 호출해야 한다. 재시도 API는 실패 원인 코드로 재시도 허용 여부를
자동 판단하지 않는다.

변환 작업자의 실패 단계·원인 저장은 [변환 실패 기록](../operations/document-conversion-failure.md)을
참고한다. #78에서 연결한 실패 안내와 수동 재시도 화면은
[히스토리 재시도 화면](../frontend/document-conversion-retry.md)에 기록한다.
서버 중단으로 남은 `PROCESSING` 작업 복구는 별도 작업이다.

## 요청과 응답

```http
POST /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/conversion-retry
X-CSRF-TOKEN: <세션의 CSRF 토큰>
Cookie: <로그인 세션>
```

요청 본문은 없다. 세 경로 ID는 양수여야 한다. 업로더와 원본 파일은 서버가 DB에서
확인하며, 클라이언트가 업로더 ID나 S3 저장 키를 전달해도 사용하지 않는다.

성공 시 본문 없는 `202 Accepted`와 `Cache-Control: no-store`를 반환한다. 이는 재시도
접수가 완료되었다는 뜻이며 PDF 변환 완료를 의미하지 않는다.

접수 시 `DocumentConversionJob.failureStage`, `failureCode`, `failedAt`을 함께 초기화한다.
접수가 롤백되면 `FAILED`와 이전 실패 정보가 그대로 유지된다. 실패 정보가 모두 `NULL`인
기존 `FAILED` 작업도 접수할 수 있다.

기존 변환 스케줄러가 `PENDING` 작업을 조회하고 변환 작업자가 같은 행을 점유하여
`PROCESSING`으로 변경한다. 자동 변환 작업자가 활성화되어 있어야 실제 변환이 진행된다.
실제 S3 다운로드·변환·PDF 업로드는 HTTP 요청 밖에서 기존 변환 흐름으로 실행한다.

## 권한과 원본 연결 검증

- 로그인한 회원이 워크스페이스 멤버이면서 선택한 버전의 업로더여야 한다.
- Document는 경로의 워크스페이스에, DocumentVersion은 경로의 Document에 속해야 한다.
- 기존 변환 작업이 존재하고 상태가 `FAILED`여야 한다.
- 연결된 업로드는 `COMPLETED`이며 같은 Document·워크스페이스·업로더를 가리켜야 한다.
- 원본 StoreFile은 같은 워크스페이스의 DOCX여야 하며 버전의 저장 키와 일치해야 한다.
- 저장 키는 `workspaces/{workspaceId}/uploads/` 범위여야 한다.
- PDF 파일이나 페이지 수가 이미 기록된 불일치 상태는 재시도하지 않는다.

API에서는 S3 파일의 실제 존재 여부를 조회하지 않는다. 원본이 S3에서 사라진 경우
접수 후 변환 작업자가 실패를 기록하며, 해당 원인을 해결해야 재변환할 수 있다.

## 오류 응답

| 상태 | 조건 |
| --- | --- |
| 400 | 경로 ID가 0 이하이거나 숫자가 아니거나 Long 범위를 벗어남 |
| 401 | 유효한 CSRF 토큰이 있지만 로그인하지 않음 |
| 403 | CSRF 누락·불일치, 워크스페이스 비멤버, 버전 업로더가 아님 |
| 404 | 워크스페이스·문서·버전이 없거나 경로의 소속 관계가 다름 |
| 409 | FAILED 이외 상태, 변환 작업 누락, 업로드·원본·PDF 연결 불일치 |

이미 접수된 요청을 반복하면 현재 상태가 `PENDING` 또는 `PROCESSING`이므로 409다.
다시 변환에 실패하여 `FAILED`가 되면 같은 API로 재시도할 수 있다.

## 동시성

트랜잭션은 `READ_COMMITTED`이며 버전 삭제와 동일하게 Document → 변환 작업 → 업로드
순서로 쓰기 잠금을 획득한다. 잠금 후 상태를 다시 읽어 동시 재시도 중 하나만 접수한다.
나머지 요청은 현재 상태에 따라 409를 반환하며 새로운 변환 작업을 생성하지 않는다.

버전 삭제가 먼저 완료되면 재시도는 404다. 재시도가 먼저 완료되면 이후 삭제는 기존
삭제 정책을 따른다. 변환 작업자가 먼저 점유하여 `PROCESSING`이면 재시도는 409다.

## 검증

- `DocumentConversionJobTest`: FAILED → PENDING 전환과 다른 상태의 전환 거부
- `DocumentConversionRetryApiTest`: 202 응답, 인증·CSRF·업로더 권한, 경로·원본 연결,
  반복 요청 거부 및 기존 리소스 유지
- `DocumentConversionRetryServiceIntegrationTest`: 실제 커밋, 대기 작업 조회·재점유,
  재변환 완료 후 같은 버전에 PDF 연결, 동시 재시도, 재실패 시 자동 반복 방지,
  재시도 롤백, 삭제와의 경합

재시도 통합 테스트는 MySQL과 모의 `DocumentConversionService`·`S3Client`를 사용한다.
실제 S3·변환 서버 연동은 이 테스트에 포함하지 않는다.


### 2026-11-20 검증 결과

운영용 `ftfm`과 분리한 임시 MySQL DB에서 아래 109개 테스트가 통과했다.
테스트 후 임시 DB와 테스트 전용 계정을 정리했다.

| 테스트 클래스 | 통과 |
| --- | ---: |
| DocumentConversionJobTest | 22 |
| DocumentConversionRetryApiTest | 40 |
| DocumentConversionRetryServiceIntegrationTest | 7 |
| DocumentConversionWorkerIntegrationTest | 15 |
| DocumentVersionDeletionApiTest | 25 |

실제 S3 및 Gotenberg를 사용한 재변환과 화면 동작은 검증하지 않았다.
