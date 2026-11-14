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

이번 단계는 URL 발급과 DocumentUpload.document 연결까지 구현한다. 기존 완료 처리는 아직 기존 Document 업로드를 거부한다. 새 DocumentVersion 등록·버전 번호 증가·변환 연결은 다음 커밋에서 구현한다.

## 검증

`DocumentVersionUploadApiTest`에서 정상 연결, 기존 V1 유지, 다른 멤버, 같은 파일명의 반복 요청, 워크스페이스 불일치, 권한·CSRF·파일 정보 검증, URL 생성 실패를 검증한다. 실제 S3 업로드는 실행하지 않는다.
