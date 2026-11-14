# 기존 Document의 새 버전 업로드 URL 발급

`POST /api/v1/workspaces/{workspaceId}/documents/{documentId}/uploads`

로그인 세션과 CSRF 토큰이 필요하다. 워크스페이스 멤버는 해당 워크스페이스의 Document를 지정하여 업로드 URL을 발급받을 수 있다.

## 요청

```json
{
  "fileName": "이력서.docx",
  "fileSize": 2048,
  "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}
```

- 기존 `CreateUploadUrlRequest`의 검증을 사용한다. 파일명은 필수·최대 255자, 파일은 DOCX·0바이트 초과·최대 20MiB다.
- `documentId`가 없거나 다른 워크스페이스의 Document이면 404, 비멤버이면 403이다.
- 경로 ID와 요청 내용이 잘못된 경우 400, 로그인하지 않은 경우 401이다.

## 응답 및 저장

- 기존 `CreateUploadUrlResponse`와 같은 `uploadId`, `uploadUrl`, `storageKey`, `expiresAt`을 반환한다.
- 응답에 `Cache-Control: no-store`를 적용한다.
- 매 요청마다 새로운 StoreFile과 PENDING 상태의 DocumentUpload를 생성한다.
- `DocumentUpload.document`에 경로로 지정한 Document를 연결한다. 파일명으로 Document를 찾거나 합치지 않는다.
- 기존 `POST /api/v1/workspaces/{workspaceId}/uploads`는 DocumentUpload.document가 null인 새 문서 업로드를 유지한다.
- URL 발급 시 DocumentVersion을 등록하거나 Document.revision을 증가시키지 않는다.

## 업로드 완료

S3 PUT 이후 기존 `POST /api/v1/uploads/{uploadId}/complete`를 호출한다.

- S3 파일 검증은 DB 트랜잭션 밖에서 수행한다.
- 최종 트랜잭션에서 DocumentUpload를 잠그고 상태·요청자·멤버십·만료·원본 파일·대상 Document를 다시 확인한다.
- 기존 Document는 행 잠금과 최신 상태 재조회 후 `revision + 1`로 DocumentVersion을 등록한다. 서로 다른 업로드가 같은 Document를 대상으로 해도 같은 버전 번호를 사용하지 않는다.
- 새 문서 업로드는 기존처럼 새 Document와 V1을 등록한다.
- DocumentVersion 저장, Document.revision 갱신, DocumentUpload 완료, PENDING 상태의 DocumentConversionJob 저장은 하나의 트랜잭션이다.
- 완료를 재요청하면 해당 DocumentUpload의 DocumentConversionJob에 연결된 DocumentVersion ID를 반환한다. 이후 V3가 등록되어도 V2 업로드의 재응답은 V2를 가리킨다.
- 기존 DocumentVersion의 원본 파일·PDF·피드백은 변경하지 않는다. 워크스페이스 문서 목록의 새 버전 업로드 버튼으로 기존 Document를 지정한다.

## 검증

`DocumentVersionUploadApiTest`에서 정상 연결, 기존 V1 유지, 다른 멤버, 같은 파일명의 반복 요청, 워크스페이스 불일치, 권한·CSRF·파일 정보 검증, URL 생성 실패를 검증한다. 실제 S3 업로드는 실행하지 않는다.

`DocumentVersionCompletionIntegrationTest`에서 V2·V3 등록, 재시도, 동시 완료, 기존 V1 파일·피드백 유지, 최종 저장 실패 시 롤백을 검증한다.

새 버전 완료 통합 테스트 15개, 기존 완료 통합 테스트 20개, 완료 서비스 테스트 8개가 통과했다. 통합 테스트는 MySQL의 실제 트랜잭션을 사용하고, S3UploadVerifier는 테스트 객체로 대체하여 실제 S3 파일 조회는 실행하지 않았다.


## 워크스페이스 화면 연결

- 문서 목록의 ‘새 버전 업로드’를 누르면 업로드 폼에 대상 Document의 제목을 표시한다. ‘새 문서로 전환’으로 선택을 취소할 수 있다.
- 파일명을 비교하지 않는다. 선택된 Document.id가 있을 때만 새 버전 URL API를 호출하며, 선택하지 않으면 기존 새 문서 API를 호출한다.
- 업로드 중이거나 완료 재시도 대기 중에는 대상 Document를 바꿀 수 없다.
- 완료 응답의 documentId가 대상 Document.id와 같은지 확인한다.
- 완료 실패 시 uploadId와 대상 documentId를 탭의 sessionStorage에 함께 보관한다. 새로고침 후에도 같은 uploadId로 완료 API만 재시도하며 S3 PUT은 다시 보내지 않는다.
- 성공 후 대상 선택을 비우고 문서 목록을 갱신한다. 목록의 버전 번호와 PDF 변환 상태는 기존 조회·폴링 로직을 사용한다.
- sessionStorage에는 세션 쿠키·CSRF 토큰·서명 URL을 저장하지 않는다.

## 프런트엔드 검증

- 업로드·문서 목록·화면 연결 관련 Node 테스트 88개가 통과했다.
- 로컬 Chrome에서 테스트 API와 S3 응답으로 대상 선택, V2 갱신, 변환 상태 폴링, 완료 실패 후 새로고침·재시도, 같은 파일명의 새 문서 등록을 확인했다.
- 데스크톱(1365×1000)과 모바일(390×844)에서 버튼과 대상 문서 표시를 확인했다. 실제 S3 문서를 사용하는 검증은 별도다.
