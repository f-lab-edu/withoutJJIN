# 피드백 내용 수정·삭제 API

두 API 모두 로그인 세션과 정상적인 `X-CSRF-TOKEN` 헤더가 필요하다.
`feedbackId`는 DocumentVersion ID가 아닌 `DocumentFeedback.id`이다.
현재 워크스페이스에 소속된 피드백 작성자만 수정·삭제할 수 있다.

## 수정

`PATCH /api/v1/feedback/{feedbackId}`

```json
{"content":"수정한 피드백 내용"}
```

- `content`는 필수이며 공백만으로 작성할 수 없고 최대 1,000자이다.
- `DocumentFeedback.content`만 변경한다. 작성자, 문서 버전, 페이지, 좌표, 색상과 작성 시각은 유지한다.
- 성공 시 200과 `DocumentFeedbackResponse`를 반환한다. `Cache-Control: no-store`를 적용한다.
- 엔티티를 조회해 내용을 변경하고 트랜잭션 변경 감지로 저장한다. 응답 DTO는 변경한 엔티티에서 생성한다.

## 삭제

`DELETE /api/v1/feedback/{feedbackId}`

- 요청 본문이 필요하지 않다.
- `DocumentFeedback` 행만 삭제한다. `DocumentVersion`, 원본과 PDF 파일은 삭제하지 않는다.
- 성공 시 본문 없는 204와 `Cache-Control: no-store`를 반환한다.
- 삭제된 피드백은 기존 GET 목록에서 제외된다. 같은 ID를 다시 삭제하면 404이다.

## 오류

| 상태 | 조건 |
| --- | --- |
| 400 | 잘못된 feedbackId 또는 수정 내용·JSON |
| 401 | 인증되지 않은 요청(유효한 CSRF로 인증 검사에 도달한 경우) |
| 403 | 워크스페이스 비멤버, 다른 작성자, CSRF 누락·불일치 |
| 404 | 존재하지 않는 DocumentFeedback |
| 415 | PATCH 요청의 Content-Type이 JSON이 아님 |

오류 요청은 기존 피드백을 수정하거나 삭제하지 않는다.
HTML 문법이 포함된 내용도 JSON 문자열로 반환한다. 화면에서는 기존처럼 `textContent`로 표시해야 한다.

## 검증

`DocumentFeedbackMutationApiTest`는 실제 Spring Security 필터와 MVC를 거치는 MockMvc 테스트다.
실제 MySQL에 저장한 엔티티를 flush·clear 후 조회하고 테스트 종료 시 롤백한다.
인증·CSRF·소속·작성자 검사, 내용과 ID 검증, 응답 상태·본문·캐시 헤더 및 변경 후 GET 결과를 확인한다.
테스트 프로필에서는 이메일·PDF 변환 스케줄러가 비활성화된다.
이번 작업은 백엔드 API까지이며 화면의 수정·삭제 버튼은 후속 작업이다.
