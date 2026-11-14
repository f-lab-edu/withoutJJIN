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
- 기존 DocumentVersion의 원본 파일·PDF·피드백은 변경하지 않는다. 화면의 새 버전 업로드 버튼 연결은 후속 커밋에서 진행한다.

## 검증

`DocumentVersionUploadApiTest`에서 정상 연결, 기존 V1 유지, 다른 멤버, 같은 파일명의 반복 요청, 워크스페이스 불일치, 권한·CSRF·파일 정보 검증, URL 생성 실패를 검증한다. 실제 S3 업로드는 실행하지 않는다.

`DocumentVersionCompletionIntegrationTest`에서 V2·V3 등록, 재시도, 동시 완료, 기존 V1 파일·피드백 유지, 최종 저장 실패 시 롤백을 검증한다.

새 버전 완료 통합 테스트 15개, 기존 완료 통합 테스트 20개, 완료 서비스 테스트 8개가 통과했다. 통합 테스트는 MySQL의 실제 트랜잭션을 사용하고, S3UploadVerifier는 테스트 객체로 대체하여 실제 S3 파일 조회는 실행하지 않았다.
