# S3 파일 삭제 작업

## 실행 흐름

1. `DocumentVersionDeletionService.deleteVersion()`이 선택한 버전의 DB 데이터를 삭제하고 원본·PDF별 `S3ObjectDeletionTask`를 같은 트랜잭션으로 저장한다.
2. 새 작업의 `nextAttemptAt`에는 `DocumentUpload.expiresAt`을 저장한다. 업로드 URL이 아직 유효한 동안 파일을 삭제해 같은 키로 다시 업로드할 수 있게 되는 상황을 피하기 위한 대기 시각이다.
3. `S3ObjectDeletionScheduler`가 실행 가능한 작업 ID를 한 번에 최대 5개 조회한다.
4. `S3ObjectDeletionTransactions.claim()`이 짧은 DB 트랜잭션에서 행을 잠그고 고유한 `claimToken`과 점유 만료 시각을 저장한다.
5. `S3ObjectDeletionWorker`가 DB 트랜잭션 밖에서 `S3ObjectDeleter.delete()`를 호출한다.
6. 성공하면 해당 토큰의 작업만 DB에서 제거한다. 실패하면 작업을 유지하고 기본 1분 뒤로 재시도 시각을 변경한다.

작업 중 서버가 종료되면 기본 2분의 점유 시간이 지난 뒤 다른 실행에서 다시 처리한다. 이전 실행이 늦게 반환되어도 토큰이 바뀐 작업을 완료하거나 재시도 상태로 덮어쓰지 않는다. S3 삭제 성공 후 DB 반영 전에 종료되면 같은 키에 삭제 요청이 다시 전송될 수 있다. S3 삭제와 DB 저장을 묶는 분산 트랜잭션은 사용하지 않는다.

## 설정

`application.yaml`의 `document.storage.deletion`에서 설정한다.

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `S3_OBJECT_DELETION_WORKER_ENABLED` | `false` | 자동 삭제 스케줄러 활성화 |
| `S3_OBJECT_DELETION_POLL_DELAY` | `10s` | 최초 실행 및 회차 사이 대기시간 |
| `S3_OBJECT_DELETION_BATCH_SIZE` | `5` | 한 회차의 최대 작업 수 |
| `S3_OBJECT_DELETION_CLAIM_LEASE` | `2m` | 작업 점유 시간 |
| `S3_OBJECT_DELETION_RETRY_DELAY` | `1m` | 실패 후 재시도 대기시간 |

실제 실행 시 `S3_OBJECT_DELETION_WORKER_ENABLED=true`를 설정하고 Spring을 재시작한다. 테스트 프로필은 외부 환경 변수와 관계없이 스케줄러를 비활성화한다. 실패한 작업에는 최대 시도 횟수를 두지 않으며, 권한 오류도 작업을 유지하고 대기 후 재시도한다.

`s3-object-deletion-` 전용 단일 스레드를 사용하므로 PDF 변환 작업과 같은 스레드에서 대기하지 않는다. 스케줄러의 Hibernate SQL 로그는 기존 필터로 숨기고 실패 로그에는 작업 ID와 오류 클래스 이름만 기록한다.

## DB 변경

`s3_object_deletion_task`에 nullable 컬럼 `next_attempt_at`, `claim_token`과 조회 인덱스를 추가했다. 현재 프로젝트의 `ddl-auto: update` 실행 환경에서는 Hibernate가 반영한다. 스키마를 수동 관리하는 환경에서는 해당 컬럼과 인덱스를 배포 전에 적용해야 한다. 기존 행의 `next_attempt_at = null`은 바로 실행 가능한 작업이므로 활성화 전에 기존 작업을 확인한다.

## S3 권한과 버전 관리

실행 계정에는 해당 버킷의 `workspaces/*/uploads/*`, `workspaces/*/review-pdfs/*`에 대한 `s3:DeleteObject` 권한이 필요하다. 버킷 전체 삭제 권한은 필요하지 않다.

현재 `S3ObjectDeleter`는 키 기반 `DeleteObject`를 사용한다. 버킷 버전 관리가 꺼져 있으면 객체를 영구 삭제한다. 버전 관리가 켜져 있거나 중지된 버킷에서는 삭제 마커 또는 과거 객체 버전이 남을 수 있다. 그 환경에서 과거 버전까지 영구 삭제하려면 S3의 `versionId` 보관과 버전별 삭제 처리가 별도로 필요하다. 이 프로젝트의 `DocumentVersion` 번호와 S3 `versionId`는 다른 개념이다.

근거: [AWS DeleteObject API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html).

## 검증 범위

별도 임시 MySQL DB와 모의 `S3Client`를 사용해 삭제 성공·재시도·점유 만료·동시 실행·DB 반영 실패·업로드 URL 만료 전 대기·파일별 부분 실패를 검증한다. 실제 S3 삭제, IAM 권한과 버킷 버전 관리 상태는 자동화 테스트로 확인하지 않는다. 삭제 API와 화면 버튼은 후속 커밋에서 연결한다.
