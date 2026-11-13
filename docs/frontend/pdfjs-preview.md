# PDF.js PDF 미리보기

워크스페이스의 `PDF 보기`는 `workspace-pdf-viewer.mjs`를 통해 PDF.js로 페이지별 canvas를 그립니다.
기존 `GET /api/v1/documents/{documentId}/pdf-url` 응답의 `pdfUrl`을 사용합니다.
페이지 번호는 각 canvas의 `data-page-number`에 기록합니다. 영역 선택과 코멘트 저장은 후속 작업입니다.

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
node --test src/test/js/workspace-pdf-viewer.test.mjs src/test/js/workspace-documents-page.test.mjs src/test/js/workspace-documents.test.mjs
```

모의 PDF.js로 여러 페이지 표시, 실패 처리, 문서 변경·닫기 중의 다운로드/그리기 취소를 확인합니다.
실제 S3 버킷의 CORS 및 PDF 표시는 위 로컬 적용 절차에서 별도로 확인합니다.
