# 버전 히스토리의 PDF 변환 실패 안내와 재시도 (#78)

## 실패 정보 표시

`document-history.html`에서 선택한 Document의 DocumentVersion 목록을 조회한다.
`conversionStatus`가 `FAILED`인 DocumentVersion에만 `failureStage`, `failureCode`,
`failedAt`에 따른 실패 단계·안내·시각을 표시한다.

- `document-versions.mjs`는 누락되거나 잘못된 실패 정보를 `null`로 정규화한다. FAILED 이외 상태에서는 이전 실패 정보를 표시하지 않는다.
- `document-conversion-failure.mjs`는 실패 코드를 고정된 안내 문구로 변환한다. 알려지지 않은 코드나 기존 NULL 데이터에는 기본 안내를 표시한다.
- `failedAt`은 서버가 반환한 시각을 표시하며 브라우저 시간대로 다시 변환하지 않는다. 시각이 없거나 형식이 잘못되면 시각 정보 없음으로 표시한다.
- 파일명·업로더 이름·실패 안내는 `textContent`로 표시한다. 서버 예외 메시지나 S3 저장 키를 표시하지 않는다.

## 재시도 흐름

1. 현재 로그인한 회원 ID와 `DocumentVersion.uploadedBy`가 같고 상태가 FAILED인 경우 PDF 변환 재시도 버튼을 표시한다. 서버의 워크스페이스 소속·업로더 검사는 별도로 유지한다.
2. 브라우저 확인창에서 버전 번호·원본 파일명·실패 안내를 보여 주고 원인 확인·해결 후 재시도하도록 안내한다. 취소하면 POST를 보내지 않는다.
3. `document-conversion-retry.mjs`는 `document-session.mjs`를 통해 `/api/v1/auth/me`를 새로 조회한다. 화면을 연 계정과 같은지 확인하고 최신 `csrfToken`을 사용한다. 버전 삭제도 같은 세션 확인 함수를 사용한다.
4. 로그인 세션과 `X-CSRF-TOKEN`을 포함하여 본문 없는 POST를 한 번 전송한다.

   `POST /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/conversion-retry`

5. 본문 없는 202는 접수 완료로 처리하고 문서·버전 목록을 다시 조회한다. 화면에서 PENDING을 임의로 설정하지 않고 실제 조회 결과를 표시한다. 바로 COMPLETED 또는 FAILED가 된 경우도 현재 상태를 표시한다.
6. PENDING·PROCESSING이 남아 있으면 기존 5초 간격 조회를 재개한다. 실제 변환은 기존 DocumentConversionJob 스케줄러·작업자가 수행한다.

확인·전송·재조회 중에는 다른 재시도, 삭제, 원본 다운로드, 수동·자동 목록 조회가 겹치지 않도록 막는다.
화면 이탈 시 요청과 타이머를 중단하며 늦은 응답으로 화면을 다시 그리지 않는다.

## 오류와 재확인

- 401·403 또는 계정 변경은 기존 비공개 목록을 비우고 로그인·권한 안내를 표시한다. CSRF 토큰이 없으면 POST를 보내지 않는다.
- POST의 404·409는 목록을 다시 조회한다. 404만 보고 기존 DocumentVersion을 숨기지 않으며, 409 이후 PROCESSING이면 재시도 버튼을 제거하고 삭제를 비활성화한다.
- 통신 오류·서버 오류 등으로 접수 여부를 확정할 수 없으면 GET으로 상태를 확인한다. POST를 자동 반복하지 않는다. 로그인 확인 단계의 오류와 POST 이후 결과 미확정은 구분한다.
- 202 이후 재조회에 실패하면 접수 완료와 조회 실패를 구분하여 안내한다. 목록 새로고침으로 현재 상태를 다시 확인할 수 있다.
- 재시도는 실패 원인을 고치는 기능이 아니다. 권한·원본 문제를 해결하지 않으면 다시 실패할 수 있다. FAILED 자동 재시도와 서버 중단으로 남은 PROCESSING 복구는 포함하지 않는다.

서버의 상태 전환·잠금·권한 검사는 [재시도 API](../api/document-conversion-retry.md)를 참고한다.

## 실행과 수동 확인

`document-history.html`과 관련 모듈 식별자는 `conversion-retry-78`이다. 실행 중인 Spring에
새 정적 파일이 반영되지 않았다면 다시 빌드·실행한 뒤 히스토리를 새로고침한다.

1. 본인이 업로드한 FAILED DocumentVersion에서 실패 안내와 재시도 버튼을 확인한다. 다른 회원의 DocumentVersion에는 재시도 버튼이 없어야 한다.
2. 실패 원인을 해결한 뒤 재시도를 취소해 POST가 없는지 확인한다. 다시 확정하면 POST 한 번과 뒤이은 목록 GET이 보여야 한다.
3. 기존 DocumentVersion의 ID·버전 번호를 유지하면서 대기·진행·완료 상태가 갱신되고 완료 후 PDF를 열 수 있는지 확인한다.
4. 권한 변경, 404·409, 통신 오류에서는 오류 안내와 실제 목록을 확인한다. 요청 결과가 불확실할 때 POST가 자동 반복되지 않아야 한다.

## 검증 결과

2026-11-22 검증:

- 관련 JavaScript 테스트 129개 통과. 실패 정보 정규화·안내, 재시도 API 호출, 히스토리 재시도, 기존 조회·다운로드·삭제를 포함한다.
- Chrome에서 실제 HTML·JavaScript와 모의 API로 본인 버튼, 실패 정보 누락·알 수 없는 코드, 확인창 취소·확정, 최신 세션·CSRF 전달과 중복 요청 차단을 확인했다.
- 접수 후 5초 간격 조회로 완료 상태 전환, 즉시 완료·재실패, 404·409, 통신 결과 미확정, 접수 후 조회 실패·수동 복구를 확인했다.
- 권한 상실·CSRF 누락·계정 변경 시 목록 제거, 기존 삭제 동작, 브라우저 실행 오류가 없음을 확인했다.
- 320·390·640·900·1365px에서 긴 파일명과 버튼의 가로 넘침이 없었고, HTML처럼 보이는 값이 실행되지 않았다. 데스크톱·모바일 너비의 캡처도 확인했다.

테스트 실행:

```powershell
node --test src/test/js/document-conversion-failure.test.mjs src/test/js/document-history-retry.test.mjs src/test/js/document-conversion-retry.test.mjs src/test/js/document-versions.test.mjs src/test/js/document-version-deletion.test.mjs src/test/js/document-history.test.mjs src/test/js/document-history-deletion.test.mjs
```

이번 브라우저 검증은 실제 Spring·DB·S3·Gotenberg에 요청하지 않았다. 실제 S3 원본으로
재변환한 PDF 확인과 실제 휴대폰 터치 검증은 별도로 확인해야 한다.
