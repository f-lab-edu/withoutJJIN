# PDF.js PDF 미리보기

워크스페이스의 `PDF 보기`를 누르면 `/workspaces/{workspaceId}/documents/{documentId}/review` 전용 화면으로 이동합니다.
`document-review.mjs`에서 문서의 소속·로그인 계정과 변환 완료 상태를 확인한 뒤,
`workspace-pdf-viewer.mjs`를 통해 PDF.js로 페이지별 canvas를 그립니다.
왼쪽은 PDF, 오른쪽은 선택한 영역의 페이지 번호를 표시하는 피드백 패널입니다.
문서 제목은 문서 목록 API에서 조회하고, 표시할 버전 번호는 PDF URL 응답의 versionNumber를 사용합니다.
PDF 드래그 영역 선택과 취소를 지원합니다. 피드백 저장·조회 및 과거 버전 선택은 후속 작업입니다.
기존 `GET /api/v1/documents/{documentId}/pdf-url` 응답의 `pdfUrl`을 사용합니다.
페이지 번호는 각 canvas의 `data-page-number`에 기록합니다. 선택 좌표는 브라우저 메모리에서 관리하며 코멘트 DB 저장은 후속 작업입니다.

## 로컬 적용

1. Spring을 재시작해 정적 파일을 반영하고 브라우저를 새로고침합니다.
2. AWS S3 콘솔에서 사용하는 버킷의 **권한 → CORS**에 `docs/aws/s3-cors-local.json`을 적용합니다.
   기존 다른 환경의 Origin/규칙이 있다면 유지하고 필요한 조회 규칙을 병합합니다.
   기존 업로드 PUT 규칙과 함께 GET/HEAD를 허용합니다.
   PDF.js는 브라우저 JavaScript에서 S3를 읽으므로 iframe과 달리 S3의 CORS 허용이 필요합니다.
   CORS는 IAM이나 버킷의 공개 설정을 대체하지 않습니다. 기존 presigned URL과 GetObject 권한을 사용합니다.
   이 저장소의 JSON을 수정하는 것만으로 AWS 설정이 바뀌지는 않습니다.
3. 변환 완료 문서에서 `PDF 보기`를 눌러 모든 페이지가 표시되는지 확인합니다.
4. 다른 문서를 열거나 `PDF 닫기`를 눌렀을 때 이전 페이지가 사라지는지 확인합니다.
5. `PDF 다시 열기`는 새 presigned URL로 PDF를 다시 읽습니다.

PDF.js 6.5.438은 `static/vendor/pdfjs/6.5.438`에 고정해 제공합니다.
브라우저는 PDF.js를 CDN에서 받지 않습니다. 공식 npm 배포 파일의 SHA-512를 확인한 후
최소화된 모듈/worker와 문자 매핑·표준 글꼴·이미지 디코딩 리소스를 포함했습니다.
라이선스와 다운로드 출처는 해당 디렉터리의 `README.md`와 `LICENSE`에 있습니다.

## 자동 검증

```powershell
node --test src/test/js/document-review.test.mjs src/test/js/workspace-pdf-viewer.test.mjs src/test/js/workspace-documents-page.test.mjs src/test/js/workspace-documents.test.mjs
```

모의 PDF.js로 여러 페이지 표시, 실패 처리, 문서 변경·닫기 중의 다운로드/그리기 취소를 확인합니다.
실제 S3 버킷의 CORS 및 PDF 표시는 위 로컬 적용 절차에서 별도로 확인합니다.


## 전용 화면 검증 결과

- 관련 JavaScript 테스트 39개 통과: 소속·계정 확인, 문서 경로 검증, PDF 다시 열기, 페이지 이동 시 취소 포함.
- `DocumentReviewPageTest` 2개 통과: HTML 경로·no-store 응답, 로그인 후 동일 화면 복귀용 RequestCache 검사.
- 임시 API 응답과 2페이지 테스트 PDF를 사용하는 Chrome 검증 통과: 제목·버전, 오른쪽 패널, 실제 PDF.js 그리기, 모바일 가로 넘침 확인.
- 위 브라우저 검증은 실제 AWS 계정이나 사용자의 문서에 접근하지 않습니다. 실제 S3 CORS 적용은 별도로 필요합니다.


## PDF 영역 선택

- PDF를 연 뒤 `영역 선택`이 켜져 있으면 드래그로 사각형을 선택합니다.
- 한 번에 한 영역만 선택합니다. 다른 페이지에서 선택하면 기존 영역을 교체합니다.
- 선택 취소 버튼 또는 PDF 영역에 포커스가 있는 상태에서 Esc로 취소합니다.
- 드래그 중 Esc나 pointercancel은 진행 중 선택만 취소하고 이전 확정 영역을 유지합니다.
- 터치로 PDF를 스크롤하려면 영역 선택 모드를 끕니다.
- 페이지 밖으로 드래그해도 처음 선택한 페이지의 경계를 넘지 않습니다.
- 좌표는 페이지 왼쪽 위 기준의 `pageNumber`, `x`, `y`, `width`, `height`로 관리합니다.
  `x`, `y`, `width`, `height`는 페이지 너비·높이에 대한 0~1 비율이며 CSS 사각형도 %로 표시합니다.
  canvas 내부 픽셀 수나 기기의 devicePixelRatio에 의존하지 않습니다.
- PDF 다시 열기 또는 화면 이동 시 작성 중 선택을 지웁니다. 피드백 등록 시 선택 영역을 서버에 전송합니다.

검증: 관련 JavaScript 테스트 30개(선택 15개 포함)와 실제 Chrome 마우스 드래그,
스크롤 후 2페이지 역방향 선택, 모바일 크기 변경 후 위치 유지, Esc 취소와 모드 전환을 확인했습니다.
브라우저 검증에는 임시 API 응답과 테스트 PDF를 사용했습니다.

```powershell
node --test src/test/js/pdf-area-selection.test.mjs src/test/js/workspace-pdf-viewer.test.mjs src/test/js/document-review.test.mjs
```

참고: https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events
참고: https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect


### 영역 색상 선택

`영역 색상`에서 초록·노랑·파랑·분홍을 선택할 수 있습니다.
선택한 영역이 있으면 색상만 즉시 변경하고 페이지 번호·좌표는 유지합니다.
선택 취소와 PDF 다시 열기 이후에도 같은 화면에서는 선택한 색상을 다음 드래그에 사용합니다.
선택 정보에 `color`(green/yellow/blue/pink)를 포함하며 피드백 등록 API로 함께 전송합니다.


## PDF 버전 식별

`GET /api/v1/documents/{documentId}/pdf-url`은 PDF URL과 동일한 DocumentVersion의
`documentVersionId` 및 `versionNumber`를 함께 반환합니다.

```json
{
  "documentVersionId": 91,
  "versionNumber": 1,
  "pdfUrl": "https://버킷의주소/문서.pdf?서명정보",
  "expiresAt": "2030-01-01T00:05:00Z"
}
```

문서 목록 조회 이후 새 버전이 등록되어도 전용 화면은 PDF 응답의 버전 번호를 표시합니다.
PDF 그리기가 완료된 이후에만 `controller.getDocumentVersionId()`로 피드백 대상 ID를 얻습니다.
PDF를 다시 열거나 화면에서 나가면 이전 ID를 지우며, 브라우저 저장소에는 저장하지 않습니다.
피드백 등록 입력창은 아래 흐름으로 연결합니다. 저장된 목록과 영역 재표시는 후속 작업입니다.


## PDF 선택 영역 피드백 등록

- PDF 그리기가 끝나면 `createDocumentReviewController`가 `view.ready()`에
  `documentVersionId`, `expectedUserId`, `pageCount`를 전달합니다.
- `createDocumentFeedbackForm`은 선택 영역과 입력 내용을 `createDocumentFeedback()`으로 전송합니다.
- 오른쪽 입력창은 최대 1,000자이며 선택 영역과 내용이 있어야 등록 버튼이 활성화됩니다.
- 등록 중 입력창, 영역 선택·색상·취소, PDF 다시 열기 버튼을 비활성화합니다.
  연속 submit 이벤트가 발생해도 진행 중에는 추가 요청을 보내지 않습니다.
- 성공하면 작성 중 내용과 선택 영역을 지우고 등록 완료 메시지를 표시합니다.
- 실패하면 내용과 선택 영역을 유지합니다. 401에는 다시 로그인 링크를 표시합니다.
- 화면 이동·PDF 다시 열기 시 요청을 취소하고 이전 응답이 새 화면에 반영되지 않도록 합니다.
  요청 취소가 서버 저장 취소를 보장하지는 않습니다.
- 목록 조회 및 저장된 영역 재표시는 다음 커밋에서 연결합니다.

```powershell
node --test src/test/js/document-feedback-form.test.mjs src/test/js/document-feedback.test.mjs src/test/js/document-review.test.mjs
```


## #51 피드백 목록 표시 연결

- PDF 그리기 완료 후 PDF URL 응답의 `documentVersionId`와 로그인 회원 ID로 목록을 조회한다.
- `document-feedback-list.mjs`는 기존 `fetchDocumentFeedback()`을 호출한다.
- 오른쪽 패널에 작성자, 페이지 번호, 내용, 작성 시각을 표시한다. 사용자 입력은 `textContent`로 표시한다.
- `createdAt`은 API가 반환한 LocalDateTime 그대로 표시하며 브라우저 시간대로 변환하지 않는다.
- 목록 로딩, 빈 목록, 조회 실패와 목록 새로고침을 지원한다. 목록 조회 실패가 PDF 표시나 입력 내용을 지우지 않는다.
- PDF 다시 열기와 화면 이탈 시 이전 목록 요청을 취소하고, 늦게 도착한 결과는 버린다.
- 목록 표시까지의 변경이다. 저장된 사각형 표시, 목록 클릭 이동과 등록 후 자동 갱신은 다음 커밋에서 연결한다.

관련 테스트:
```powershell
node --test src/test/js/document-feedback-list.test.mjs src/test/js/document-feedback.test.mjs src/test/js/document-feedback-form.test.mjs src/test/js/document-review.test.mjs
```


## #51 저장된 영역 표시와 목록 이동

- `pdf-feedback-regions.mjs`가 조회된 피드백마다 해당 페이지에 사각형을 표시한다.
- 좌표와 크기를 페이지 기준 %로 표시하므로 화면 크기가 바뀌어도 같은 문서 위치를 가리킨다.
- 저장된 영역은 `.pdf-feedback-region`, 작성 중 영역은 `.pdf-selection`으로 별도로 관리한다.
- 목록 항목은 버튼이다. 클릭 또는 키보드 Enter/Space로 해당 사각형 위치로 스크롤하고 활성 항목과 영역을 강조한다.
- 목록 재조회, 실패, PDF 다시 열기, 화면 이탈 때 저장된 영역을 지운다. 목록 새로고침은 작성 중 선택 영역을 지우지 않는다.
- 영역은 마우스 이벤트를 가로채지 않으므로 저장된 영역 위에서도 새 영역을 선택할 수 있다.
- 등록 성공 후 목록과 영역의 자동 갱신은 다음 커밋에서 연결한다.

관련 테스트:
```powershell
node --test src/test/js/pdf-feedback-regions.test.mjs src/test/js/document-feedback-list.test.mjs src/test/js/pdf-area-selection.test.mjs src/test/js/document-review.test.mjs
```


## #51 등록 성공 후 목록·영역 자동 갱신

- POST 성공 후 입력과 작성 중 선택을 비우고 현재 `documentVersionId`의 GET 목록을 다시 조회한다.
- GET 결과의 작성자·내용·시각과 저장된 PDF 영역을 함께 표시한다. 등록 POST를 다시 보내지 않는다.
- POST 성공 후 GET이 실패하면 등록 성공 안내는 유지하고, 목록에 등록 완료 사실과 ‘목록 새로고침’ 안내를 표시한다.
- POST 실패 시에는 입력과 선택 영역을 유지하고 자동 목록 갱신을 실행하지 않는다.
- 등록 전 시작한 목록 조회는 갱신 요청이 취소한다. PDF를 바꾸거나 화면을 떠난 뒤 도착한 결과는 적용하지 않는다.
- 실제 브라우저에서 영역 선택 → 등록 → 목록·영역 반영 → 새로고침 후 유지 → 목록 클릭 이동을 별도로 확인한다.

관련 테스트:
```powershell
node --test src/test/js/document-feedback-list.test.mjs src/test/js/document-feedback-form.test.mjs src/test/js/document-feedback.test.mjs src/test/js/document-review.test.mjs src/test/js/pdf-feedback-regions.test.mjs src/test/js/pdf-area-selection.test.mjs
```


## #55 본인 피드백 수정 화면

- 현재 PDF를 연 로그인 회원의 ID와 `createdBy`가 같은 항목에만 수정 버튼을 표시한다.
- 수정 버튼은 기존 내용을 별도 입력창에 불러온다. 내용은 `textarea.value`로 설정한다.
- 수정 취소는 PATCH를 보내지 않으며, 실패하면 입력 내용을 유지한다.
- 저장 중 중복 제출을 막고 수정·취소 버튼을 비활성화한다.
- 수정 중에는 등록·영역 선택·PDF 다시 열기·목록 새로고침을 잠시 막아 작성 중 내용을 보존한다.
- 수정 성공 후 GET 목록을 다시 조회하여 목록과 저장된 영역을 표시한다. 영역 좌표·색상은 변경하지 않는다.
- PATCH 성공 후 GET 실패 시 수정 완료 사실과 목록 새로고침 안내를 표시한다.
- PDF 화면 이탈과 재로딩은 진행 중 PATCH를 취소하고 이전 결과를 무시한다.
- 삭제 버튼과 삭제 확인 안내는 다음 커밋에서 연결한다. 실제 브라우저 확인은 별도로 진행한다.
