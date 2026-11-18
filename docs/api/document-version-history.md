# DocumentVersion 목록·파일 조회 API

```http
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions
```

로그인한 워크스페이스 멤버가 해당 Document의 DocumentVersion을 조회한다. 업로더 외의 멤버도 조회할 수 있다.

## 응답

버전 번호 내림차순 배열을 반환하며 `Cache-Control: no-store`를 적용한다.

```json
[
  {
    "id": 91,
    "documentId": 10,
    "versionNumber": 3,
    "originalFileName": "서비스 기획서 V3.docx",
    "fileSize": 1024,
    "pageCount": 2,
    "uploadedBy": 7,
    "uploadedByDisplayName": "업로더",
    "createdAt": "2026-11-15T12:00:00",
    "conversionStatus": "COMPLETED"
  }
]
```

- `id`는 DocumentVersion.id이며 `documentId`는 Document.id이다.
- `uploadedBy`는 DocumentVersion.uploadedBy의 AppUser.id이다.
- `createdAt`은 DocumentVersion.createdAt이며 Asia/Seoul 기준 LocalDateTime이다.
- `conversionStatus`는 해당 DocumentVersion의 DocumentConversionJob.status이다. PENDING, PROCESSING, COMPLETED, FAILED 중 하나다.
- DocumentConversionJob이 아직 없으면 conversionStatus는 null이다. 다른 버전의 상태로 대체하지 않는다.
- 변환 전 pageCount와 업로더의 uploadedByDisplayName은 null일 수 있다.
- DocumentVersion이 없는 Document는 빈 배열을 반환한다.
- S3 저장 키, 사용자 이메일, PDF·원본 다운로드 URL은 반환하지 않는다.

## 권한 및 오류

워크스페이스 멤버임을 확인한 뒤 Document가 요청한 워크스페이스에 속하는지 검사한다.

| 상황 | 응답 |
| --- | --- |
| 정상 조회·빈 목록 | 200 |
| 잘못된 workspaceId 또는 documentId | 400 |
| 비로그인 | 401 |
| 워크스페이스 비멤버 | 403 |
| 워크스페이스 또는 소속 Document가 없음 | 404 |

## 구현 범위

버전 목록 조회와 선택한 버전의 PDF·원본 URL 발급을 제공한다. 문서 카드·히스토리 화면은 다음 커밋에서 연결한다. 버전 삭제와 검토 상태 관리는 포함하지 않는다.

조회 테스트에서는 실제 S3에 접근하지 않는다. DocumentVersion 목록과 변환 상태는 테스트 전용 MySQL 스키마에서 검증한다.

## 선택한 DocumentVersion의 PDF URL

```http
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/pdf-url
```

기존 최신 PDF API와 동일한 PdfUrlResponse를 반환한다. documentVersionId, versionNumber, pdfUrl, expiresAt은 모두 선택한 DocumentVersion을 기준으로 한다.

- DocumentConversionJob.status가 COMPLETED이고 PDF StoreFile이 정상적으로 연결되어 있어야 한다.
- 선택한 버전이 준비되지 않았으면 409를 반환하며 다른 버전의 PDF로 대체하지 않는다.
- 기존 `GET /api/v1/documents/{documentId}/pdf-url`은 최신 버전 조회로 유지한다.

## 선택한 DocumentVersion의 원본 URL

```http
GET /api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/original-url
```

```json
{
  "documentVersionId": 91,
  "versionNumber": 3,
  "originalFileName": "서비스 기획서 V3.docx",
  "downloadUrl": "https://example.invalid/original.docx?서명정보",
  "expiresAt": "2026-11-15T03:05:00Z"
}
```

- DocumentUpload.status가 COMPLETED이고 원본 StoreFile의 워크스페이스·저장 키가 해당 DocumentVersion과 일치해야 한다.
- 원본은 PDF 변환이 대기·실행 중·실패 상태여도 발급할 수 있다.
- DOCX 형식과 attachment 응답으로 다운로드하며 UTF-8 원본 파일명을 적용한다.
- 파일명이 비어 있거나 제어 문자가 포함된 경우 서명하지 않는다.

## 파일 URL의 공통 처리

- 워크스페이스 소속, Document의 소속, DocumentVersion의 소속을 모두 확인한다.
- 잘못된 ID는 400, 비로그인은 401, 비멤버는 403, 소속 문서·버전이 없으면 404를 반환한다.
- 원본·PDF 연결이 없거나 일치하지 않으면 409를 반환한다.
- 저장 키는 클라이언트가 전달한 쿼리 파라미터 대신 DB에서 확인한 값을 사용한다.
- API 응답과 S3 GET 응답에 no-store를 적용한다. URL 기본 유효기간은 기존 설정과 같은 5분이다.
- 이미 발급된 URL은 유효기간 동안 사용될 수 있다. 새 URL 발급 시에는 매번 멤버십을 확인한다.
- 자동화 테스트에서는 임시 MySQL 스키마와 가짜 인증 정보로 검증한다. 실제 S3에 요청하거나 파일을 다운로드하지 않는다.

## 버전 삭제 이후 최신 버전 조회

- 문서 목록 API `GET /api/v1/workspaces/{workspaceId}/documents`의 `revision`은 현재 남은 `DocumentVersion.versionNumber`의 최댓값이다. 버전이 없으면 0이다.
- `versionCount`는 남은 DocumentVersion 행 수이며, `conversionStatus`는 현재 남은 최대 번호의 버전에 연결된 DocumentConversionJob 상태다. 연결된 작업이 없으면 null이다.
- `GET /api/v1/documents/{documentId}/pdf-url`도 현재 남은 최대 번호의 DocumentVersion을 조회한다. 그 버전의 PDF가 준비되지 않았으면 409이며, 더 낮은 번호의 완료 PDF를 대신 반환하지 않는다.
- V3을 삭제해 V1·V2가 남았다면 목록과 최신 PDF 조회는 V2를 가리킨다. 번호 발급에 사용하는 `Document.revision`은 3으로 유지하여 다음 업로드에는 V4를 발급한다.
- 마지막 버전까지 삭제해도 Document는 목록에 남는다. 응답은 revision=0, versionCount=0, conversionStatus=null이며 버전 히스토리는 빈 배열이다. 최신 PDF URL은 발급하지 않는다(409).
- 삭제한 DocumentVersion의 버전별 PDF·원본 URL 조회는 404다.

삭제 이후 조회 검증은 `DocumentQueryAfterVersionDeletionApiTest`에서 실제 DELETE API를 호출한 뒤 목록·최신 PDF·히스토리·버전별 URL 응답을 확인한다. S3 URL 생성은 모의 객체로 대체하며, 화면 버튼은 후속 커밋에서 연결한다. 삭제 API는 `docs/api/document-version-deletion.md`를 참고한다.
