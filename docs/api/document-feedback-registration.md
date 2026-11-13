# PDF 선택 영역 피드백 등록

## 요청

`POST /api/v1/document-versions/{documentVersionId}/feedback`

`documentVersionId`에는 PDF 조회 응답에서 받은 `DocumentVersion.id`를 사용합니다.
문서 ID나 화면에 표시되는 V1·V2 번호와 다른 값입니다.

로그인 세션 쿠키, `X-CSRF-TOKEN` 헤더, `Content-Type: application/json`이 필요합니다.
현재 프로젝트에서는 로그인 정보 조회 응답의 `csrfToken`을 사용합니다.
브라우저는 같은 출처로 요청할 때 세션 쿠키를 전송합니다.

```json
{
  "pageNumber": 1,
  "x": 0.1,
  "y": 0.2,
  "width": 0.2,
  "height": 0.3,
  "color": "green",
  "content": "이 문장을 수정해 주세요."
}
```

- 좌표는 PDF 페이지 왼쪽 위 기준의 0~1 비율입니다.
- 페이지 번호는 1부터 시작하며 `DocumentVersion.pageCount`를 넘을 수 없습니다.
- 너비와 높이는 0보다 커야 하며 선택 영역이 페이지 경계를 넘을 수 없습니다.
- 색상은 `green`, `yellow`, `blue`, `pink`를 허용합니다.
- 내용은 공백뿐일 수 없으며 최대 1,000자입니다.
- 작성자는 요청 본문에서 받지 않고 OIDC 로그인 정보로 조회한 `AppUser`로 결정합니다.
- 이미 새 버전이 등록되어도 권한과 변환 완료 조건을 만족하는 기존 `DocumentVersion`에 등록할 수 있습니다.

## 응답

정상 등록 시 `201 Created`, `Cache-Control: no-store`와 함께 저장 결과를 반환합니다.

```json
{
  "id": 501,
  "documentVersionId": 91,
  "createdBy": 7,
  "pageNumber": 1,
  "x": 0.1,
  "y": 0.2,
  "width": 0.2,
  "height": 0.3,
  "color": "green",
  "content": "이 문장을 수정해 주세요.",
  "createdAt": "2030-01-01T12:00:00"
}
```

| 상태 | 조건 |
| --- | --- |
| 400 | 잘못된 ID, JSON 또는 페이지·좌표·색상·내용 |
| 401 | 로그인되지 않은 요청. CSRF 검사를 통과한 요청 기준 |
| 403 | 워크스페이스 비멤버 또는 누락·유효하지 않은 CSRF 토큰 |
| 404 | 대상 DocumentVersion이 없음 |
| 409 | 해당 버전의 PDF 변환·페이지 수·PDF 연결이 준비되지 않음 |
| 415 | application/json이 아닌 요청 |

## 실행 흐름

`DocumentFeedbackController` → `DocumentFeedbackService` → `DocumentFeedbackRepository.save()`

Controller는 요청 검증과 로그인 정보 전달을 담당합니다.
Service는 회원·문서 버전·워크스페이스 소속·PDF 준비 여부를 검사하고,
`DocumentFeedback` 저장과 응답 변환을 하나의 트랜잭션에서 처리합니다.
피드백 목록 조회는 [문서 버전별 피드백 조회](document-feedback-query.md)를 참고합니다.
PDF 화면의 입력창과 피드백 목록 연결은 후속 작업입니다.

## 검증

```powershell
.\gradlew.bat test --tests withoutjjin.withoutjjin.domain.feedback.DocumentFeedbackApiTest
```

MockMvc와 실제 DB를 사용하고 테스트 종료 시 데이터를 롤백합니다.
테스트 프로필에서 자동 메일 발송과 PDF 변환 작업자는 실행하지 않습니다.
실제 S3나 Google 로그인 서버에는 요청하지 않습니다.
