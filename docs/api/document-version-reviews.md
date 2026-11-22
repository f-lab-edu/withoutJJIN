# DocumentVersion별 피드백 완료 API (#80)

## 의미와 범위

회원이 PDF 검토를 마치고 피드백 완료를 명시적으로 표시한다.
`DocumentVersionReview` 한 행은 특정 DocumentVersion에 대해 특정 회원이 피드백을 완료했다는 뜻이다.

- DocumentVersion별로 완료한 회원과 완료 시각을 저장한다. V1의 완료 기록은 V2로 이어지지 않는다.
- 같은 워크스페이스의 여러 회원이 각각 완료할 수 있다. 업로더도 본인의 완료를 표시할 수 있다.
- 검토자를 미리 지정하거나 다른 회원의 완료를 대신 등록하지 않는다. `Document.approver`는 사용하거나 변경하지 않는다.
- 의견이 없는 검토도 완료할 수 있으므로 DocumentFeedback 행이 반드시 있어야 하는 것은 아니다.
- 피드백 등록·수정·삭제는 기존 권한과 동작을 유지한다. 완료 기록을 자동 생성하거나 초기화하지 않는다.
- 한 회원의 완료가 DocumentVersion 전체나 모든 회원의 검토 완료를 뜻하지 않는다.

이번 작업은 등록·조회 API까지 포함한다. 완료 버튼·회원별 표시 화면, 완료 취소,
검토자 지정·검토 요청·승인 상태 관리는 포함하지 않는다.

## 본인의 피드백 완료 등록

```http
PUT /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/review-completion
X-CSRF-TOKEN: <로그인 세션의 CSRF 토큰>
Cookie: <로그인 세션>
```

요청 본문은 없다. 회원 ID와 완료 시각은 클라이언트 입력 대신 로그인한 AppUser와 서버 시각을 사용한다.

최초 등록과 반복 등록 모두 `200 OK`, `Cache-Control: no-store`와 완료 기록을 반환한다.
반복 클릭·통신 재전송이 새 기록이나 새로운 완료 시각을 만들지 않도록 PUT을 사용한다.

```json
{
  "id": 101,
  "documentVersionId": 721,
  "reviewerId": 7,
  "reviewerDisplayName": "임형준",
  "completedAt": "2026-11-22T12:34:56.123456"
}
```

숫자와 시각은 예시다. `completedAt`은 Asia/Seoul 기준이며 오프셋 없는 LocalDateTime 형식이다.
첫 응답과 재조회·재요청의 시각이 일치하도록 MySQL 저장 정밀도인 마이크로초 단위를 사용한다.

등록 조건:

- 로그인한 회원이 경로의 워크스페이스 멤버여야 한다.
- Document는 경로의 워크스페이스, DocumentVersion은 경로의 Document에 속해야 한다.
- DocumentConversionJob 상태가 COMPLETED이고 페이지 수가 확인되어야 한다.
- 완료된 DocumentUpload가 같은 문서·워크스페이스에 연결되어야 한다.
- 연결된 PDF StoreFile이 같은 워크스페이스에 속하고 Content-Type이 application/pdf여야 한다.

검사는 DB를 사용하며 S3 파일 존재 확인이나 PDF 변환을 호출하지 않는다.
완료 등록으로 DocumentVersion·DocumentFeedback·DocumentUpload·StoreFile을 변경하지 않는다.

## 완료한 회원 목록 조회

```http
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/reviews
Cookie: <로그인 세션>
```

워크스페이스 멤버는 해당 버전에서 완료한 회원의 기록을 `completedAt`, `id` 오름차순으로 조회한다.
응답은 위 완료 기록의 배열이며 `200 OK`, `Cache-Control: no-store`를 사용한다.
완료한 회원이 없으면 빈 배열 `[]`이다. 조회는 완료 기록을 생성하지 않는다.

조회 결과는 완료한 회원만 포함한다. 응답에 없는 회원은 완료 기록이 없다는 뜻이며,
등록 조건과 달리 완료 기록 조회 자체에는 PDF 변환 완료를 요구하지 않는다.
파일 저장 키·문서 내용·회원 이메일·OIDC 식별 정보는 응답에 포함하지 않는다.

## 오류 응답

| 상태 | 조건 |
| --- | --- |
| 400 | 경로 ID가 양수가 아니거나 숫자가 아니거나 Long 범위를 벗어남 |
| 401 | 로그인 세션이 없음. PUT은 유효한 CSRF를 전달한 경우 기준 |
| 403 | 워크스페이스 비멤버 또는 PUT의 CSRF 누락·불일치 |
| 404 | 경로의 워크스페이스·문서·버전이 없거나 소속 관계가 다름 |
| 409 | PUT에서 PDF 변환이 완료되지 않았거나 PDF 연결·페이지 수가 불일치 |

이미 완료한 회원의 정상적인 반복 PUT은 409가 아니라 기존 기록을 반환한다.

## 중복 요청과 버전 삭제

- `(document_version_id, reviewer_id)` 유일 제약으로 같은 회원·같은 버전의 중복 행을 막는다.
- 등록 트랜잭션은 READ_COMMITTED이며 기존 버전 삭제와 같은 Document 쓰기 잠금을 사용한다.
- 같은 회원의 동시 요청도 먼저 커밋된 완료 기록을 반환하며, 다른 회원의 완료는 별도로 저장한다.
- 버전 삭제가 먼저 끝났으면 완료 등록은 404다. 완료 등록이 먼저 끝났으면 버전 삭제가 완료 기록까지 함께 삭제한다.
- DocumentVersion 삭제 시 해당 버전의 DocumentVersionReview를 같은 트랜잭션에서 삭제한다. 다른 버전의 완료 기록은 유지한다.
- 삭제 트랜잭션이 롤백되면 완료 기록도 복원된다. 실제 S3 삭제는 기존 S3ObjectDeletionTask 흐름을 유지한다.

## DB 변경

새 테이블 `document_version_review`에 다음 컬럼을 추가한다.

| 컬럼 | 의미 |
| --- | --- |
| id | 피드백 완료 기록 ID, 기본키 |
| document_version_id | DocumentVersion 외래키 |
| reviewer_id | 피드백을 완료한 AppUser 외래키 |
| completed_at | 서버에서 기록한 완료 시각 |

유일 제약 이름은 `uk_version_review_version_reviewer`다. 기존 문서·피드백에 완료 기록을 일괄 생성하지 않는다.
로컬의 기존 `ddl-auto: update` 설정에서는 애플리케이션 시작 시 새 테이블을 생성한다.
운영 환경에서 자동 DDL을 사용하지 않으면 배포 전에 같은 테이블·외래키·유일 제약을 반영해야 한다.

## 검증 결과

2026-11-22, 개발용 ftfm과 분리한 임시 MySQL DB에서 관련 테스트 109개가 통과했다.
테스트 종료 후 임시 DB와 테스트 계정을 정리했다.

| 테스트 클래스 | 통과 |
| --- | ---: |
| DocumentVersionReviewTest | 4 |
| DocumentVersionReviewRepositoryTest | 5 |
| DocumentVersionReviewApiTest | 30 |
| DocumentVersionReviewServiceIntegrationTest | 8 |
| DocumentVersionTest | 6 |
| DocumentVersionDeletionApiTest | 25 |
| DocumentVersionDeletionServiceIntegrationTest | 23 |
| DocumentQueryAfterVersionDeletionApiTest | 8 |

API 테스트는 로그인·CSRF·멤버 권한, ID·소속 검증, 변환 상태와 PDF 연결,
응답 필드 제한, 본인 기록만 저장, 반복 요청 시 ID·시각 유지,
버전별 목록과 기존 피드백·파일 연결 유지를 확인했다.

트랜잭션 테스트는 실제 커밋·롤백, 같은 회원·다른 회원의 동시 완료,
완료 등록과 버전 삭제의 경합, 삭제 후 다른 버전 기록 유지와 삭제 롤백 시 복원을 확인했다.
테스트는 S3Client를 모의 객체로 대체하고 외부 요청이 없음을 확인했다.
실제 S3·Gotenberg 연동과 화면 동작은 이번 API 검증에 포함하지 않는다.
