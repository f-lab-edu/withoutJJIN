# DocumentVersion 삭제 API

```http
DELETE /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}
```

로그인한 워크스페이스 멤버 중 해당 DocumentVersion의 업로더만 삭제할 수 있다. Document 생성자라도 다른 회원이 업로드한 버전은 삭제할 수 없다.

## 요청

- `workspaceId`, `documentId`, `documentVersionId`: 양수 Long 경로 변수
- 로그인 세션 쿠키와 유효한 CSRF 헤더 필요
- 요청 본문 없음
- 회원 ID나 S3 저장 키를 클라이언트에서 받지 않는다. 요청자는 로그인한 OidcUser의 issuer·subject로 조회한다.

## 성공 응답

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

응답 본문은 없다. 204는 선택한 DocumentVersion과 관련 DB 데이터 삭제 및 S3ObjectDeletionTask 등록이 커밋되었다는 의미다. 실제 S3 파일 삭제 완료를 의미하지 않는다.

## 삭제 범위

- 선택한 DocumentVersion과 그 버전의 모든 DocumentFeedback
- 연결된 DocumentConversionJob과 DocumentUpload
- 원본·PDF의 StoreFile
- 원본·PDF의 저장 키를 S3ObjectDeletionTask에 등록

다른 DocumentVersion과 그 파일·피드백은 유지한다. 마지막 버전을 삭제해도 Document는 유지하며, 번호 발급을 위한 Document.revision은 감소하지 않는다. 같은 버전의 삭제를 다시 요청하면 404이며 S3ObjectDeletionTask를 중복 등록하지 않는다.

PDF 변환 대기·실패 버전도 삭제할 수 있다. PROCESSING 상태는 변환이 끝난 뒤 다시 삭제해야 한다.

## 오류

| 상태 | 조건 |
| --- | --- |
| 400 | 0·음수·숫자가 아닌 값·Long 범위 초과 경로 ID |
| 401 | 로그인하지 않은 요청(유효한 CSRF가 전달된 경우) |
| 403 | CSRF 누락·불일치, 워크스페이스 비멤버 또는 다른 업로더 |
| 404 | 없는 워크스페이스·Document·DocumentVersion, 요청 경로의 소속 관계 불일치 |
| 409 | PDF 변환 중, DocumentUpload·StoreFile 연결 불일치 또는 다른 버전의 파일 참조가 남아 있음 |

CSRF 검사는 인증 검사보다 먼저 실행될 수 있어 미로그인 요청도 CSRF가 없으면 403을 받을 수 있다. DB 삭제와 S3ObjectDeletionTask 등록 중 실패하면 같은 트랜잭션의 DB 변경을 모두 롤백하며 204를 반환하지 않는다.

## 삭제 이후 조회

- 목록과 최신 PDF 조회는 현재 남은 최대 DocumentVersion.versionNumber를 기준으로 한다.
- V3 삭제 후 V1·V2가 남으면 목록과 최신 PDF는 V2를 가리키며, 다음 업로드에는 V4가 발급된다.
- 남은 최신 버전의 PDF가 아직 준비되지 않으면 최신 PDF URL은 409이며 이전 완료 버전으로 대체하지 않는다.
- 모든 버전 삭제 후 목록에는 Document가 남고 revision=0, versionCount=0, conversionStatus=null이다. 히스토리는 빈 배열이며 최신 PDF URL은 409다.
- 삭제한 버전의 PDF·원본 URL 조회는 404다.

## S3 파일 정리

자동 정리를 실행하려면 `S3_OBJECT_DELETION_WORKER_ENABLED=true`로 Spring을 실행해야 한다. 최초 삭제 작업은 DocumentUpload.expiresAt 이후 실행하고, 실패한 파일은 재시도한다. 자세한 설정과 필요한 S3 권한·버전 관리 제약은 `docs/operations/s3-object-deletion.md`를 참고한다.

## 검증

`DocumentVersionDeletionApiTest`에서 로그인·CSRF·경로 검증·권한·204 응답·DB 삭제·작업 등록·변환 중 거부를 확인한다. `DocumentQueryAfterVersionDeletionApiTest`는 실제 DELETE API를 호출한 뒤 문서 목록·히스토리·PDF 조회 결과를 검증한다. 테스트에는 임시 MySQL DB와 모의 S3를 사용하며, 실제 파일 삭제와 브라우저 버튼은 검증하지 않는다.
