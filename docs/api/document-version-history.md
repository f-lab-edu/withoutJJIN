# DocumentVersion 목록 조회 API

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

이번 커밋은 버전 목록 조회 API까지 구현한다. 문서 카드·히스토리 화면, 선택한 버전의 PDF·원본 URL 발급은 다음 커밋에서 연결한다. 버전 삭제와 검토 상태 관리는 포함하지 않는다.

조회 테스트에서는 실제 S3에 접근하지 않는다. DocumentVersion 목록과 변환 상태는 테스트 전용 MySQL 스키마에서 검증한다.
