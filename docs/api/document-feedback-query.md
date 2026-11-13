# 문서 버전별 PDF 영역 피드백 조회

## 요청

`GET /api/v1/document-versions/{documentVersionId}/feedback`

`documentVersionId`에는 PDF 조회 URL 응답의 `DocumentVersion.id`를 사용합니다.
`Document.id`나 화면의 V1·V2 번호와 다른 값입니다.

로그인 세션 쿠키가 필요합니다. 같은 출처에서 브라우저가 세션 쿠키를 전송합니다.
조회 GET 요청에는 CSRF 토큰과 요청 본문이 필요하지 않습니다.
`DocumentVersion`이 속한 워크스페이스의 멤버만 조회할 수 있습니다.

## 응답

정상 조회 시 `200 OK`, `Content-Type: application/json`, `Cache-Control: no-store`를 반환합니다.

```json
[
  {
    "id": 501,
    "documentVersionId": 91,
    "createdBy": 7,
    "createdByDisplayName": "피드백 작성자",
    "pageNumber": 1,
    "x": 0.1,
    "y": 0.2,
    "width": 0.2,
    "height": 0.3,
    "color": "green",
    "content": "이 문장을 수정해 주세요.",
    "createdAt": "2030-01-01T12:00:00"
  }
]
```

- 지정한 `DocumentVersion`의 `DocumentFeedback`만 반환합니다. 이전 버전도 조회할 수 있습니다.
- `createdAt` 오름차순으로 정렬하고, 같은 시각이면 `id` 오름차순으로 정렬합니다.
- `createdBy`는 작성자 `AppUser.id`, `createdByDisplayName`은 조회 시점의 `AppUser.displayName`입니다.
- `AppUser.displayName`이 없으면 `createdByDisplayName`은 `null`입니다.
- 좌표는 PDF 페이지 왼쪽 위 기준의 0~1 비율이며 페이지 번호는 1부터 시작합니다.
- 이메일, OIDC issuer·subject, S3 저장 키는 응답에 포함하지 않습니다.
- 피드백이 없으면 `[]`를 반환합니다. PDF 변환 전에도 멤버는 빈 목록을 조회할 수 있습니다.
- 현재 API는 페이지 나누기 없이 해당 버전의 전체 피드백을 반환합니다.

| 상태 | 조건 |
| --- | --- |
| 200 | 정상 조회. 피드백이 없는 경우도 포함 |
| 400 | 0 이하, 숫자가 아닌 값, Long 범위를 벗어난 DocumentVersion ID |
| 401 | 로그인되지 않은 요청 |
| 403 | 대상 DocumentVersion의 워크스페이스 비멤버 |
| 404 | 대상 DocumentVersion이 없음 |

## 실행 흐름

`DocumentFeedbackController.getFeedbacks()` → `DocumentFeedbackService.getFeedbacks()`
→ `DocumentFeedbackRepository.findAllByDocumentVersionId()`

`DocumentFeedbackService`는 `@Transactional(readOnly = true)` 안에서 `AppUser`와 `DocumentVersion`을 조회하고,
`WorkspaceService.getWorkSpaceForMember()`로 워크스페이스 소속을 검사합니다.
권한 검사 후 `DocumentFeedback`과 작성자 `AppUser`를 함께 조회하여
트랜잭션 안에서 `DocumentFeedbackListItemResponse`로 변환합니다.

PDF 화면에서 등록 API 호출, 목록 표시, 저장된 선택 영역 표시를 연결하는 작업은 포함하지 않습니다.

## 검증

```powershell
.\gradlew.bat test --tests withoutjjin.withoutjjin.domain.feedback.DocumentFeedbackQueryApiTest
```

MockMvc와 실제 DB를 사용하며 테스트 데이터는 종료 시 롤백합니다.
외부 Google 로그인 서버나 S3에 요청하지 않습니다.

피드백 등록은 [PDF 선택 영역 피드백 등록](document-feedback-registration.md)을 참고합니다.
