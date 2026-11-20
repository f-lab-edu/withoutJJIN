package withoutjjin.withoutjjin.domain.document.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DocumentConversionRetryService {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentConversionJobRepository conversionJobs;
    private final DocumentUploadRepository uploads;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retryConversion(Long workspaceId, Long documentId, Long documentVersionId,
                                String issuer, String subject) {
        requirePositiveId(workspaceId, "workspaceId");
        requirePositiveId(documentId, "documentId");
        requirePositiveId(documentVersionId, "documentVersionId");
        AppUser requester = appUserService.getByIssuerAndSubject(issuer, subject);
        workspaceService.getWorkSpaceForMember(workspaceId, requester);

        // 버전 삭제와 같은 순서(Document → 변환 작업 → 업로드)로 잠근다.
        Document document = documents.findByIdForUpdate(documentId)
                .filter(found -> Objects.equals(found.getWorkspace().getId(), workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "워크스페이스에서 Document를 찾을 수 없습니다."));
        DocumentVersion version = versions.findByIdAndDocumentId(documentVersionId, document.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document에서 DocumentVersion을 찾을 수 없습니다."));
        if (!Objects.equals(version.getUploadedBy().getId(), requester.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "DocumentVersion의 업로더만 PDF 변환을 재시도할 수 있습니다.");
        }

        DocumentConversionJob job = conversionJobs.findByDocumentVersionIdForUpdate(documentVersionId)
                .orElseThrow(DocumentConversionRetryService::inconsistentSource);
        entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
        if (job.getStatus() != DocumentConversionJobStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PDF 변환에 실패한 DocumentVersion만 재시도할 수 있습니다.");
        }
        DocumentUpload upload = uploads.findByIdForUpdate(job.getDocumentUpload().getId())
                .orElseThrow(DocumentConversionRetryService::inconsistentSource);
        entityManager.refresh(upload, LockModeType.PESSIMISTIC_WRITE);
        validateSource(workspaceId, version, upload);

        // 기존 행을 재사용한다. 커밋 후 기존 스케줄러가 PENDING 작업을 조회한다.
        job.retry();
    }

    private void validateSource(Long workspaceId, DocumentVersion version, DocumentUpload upload) {
        StoreFile original = upload.getOriginalFile();
        if (upload.getStatus() != DocumentUploadStatus.COMPLETED || upload.getDocument() == null
                || !Objects.equals(upload.getDocument().getId(), version.getDocument().getId())
                || !Objects.equals(upload.getWorkspace().getId(), workspaceId)
                || !Objects.equals(upload.getCreatedBy().getId(), version.getUploadedBy().getId())
                || original == null || !Objects.equals(original.getWorkspace().getId(), workspaceId)
                || !Objects.equals(original.getObjectKey(), version.getStorageKey())
                || !original.getObjectKey().startsWith("workspaces/" + workspaceId + "/uploads/")
                || !DOCX_MEDIA_TYPE.equals(original.getMediaType())
                || upload.getReviewPdfFile() != null || version.getPageCount() != null) {
            throw inconsistentSource();
        }
    }

    private static void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + "는 양수여야 합니다.");
        }
    }

    private static ResponseStatusException inconsistentSource() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "DocumentVersion의 변환 작업 또는 원본 파일 연결을 확인할 수 없습니다.");
    }
}
