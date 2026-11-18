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
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DocumentVersionDeletionService {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentConversionJobRepository conversionJobs;
    private final DocumentUploadRepository uploads;
    private final DocumentFeedbackRepository feedbacks;
    private final StoreFileRepository files;
    private final S3ObjectDeletionTaskRepository deletionTasks;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteVersion(Long workspaceId, Long documentId, Long documentVersionId,
                              String issuer, String subject) {
        requirePositiveId(workspaceId, "workspaceId");
        requirePositiveId(documentId, "documentId");
        requirePositiveId(documentVersionId, "documentVersionId");
        AppUser requester = appUserService.getByIssuerAndSubject(issuer, subject);
        workspaceService.getWorkSpaceForMember(workspaceId, requester);

        // 새 버전 등록과 같은 Document 잠금을 사용한다. revision은 발급한 번호로 유지한다.
        Document document = documents.findByIdForUpdate(documentId)
                .filter(found -> Objects.equals(found.getWorkspace().getId(), workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "워크스페이스에서 Document를 찾을 수 없습니다."));
        DocumentVersion version = versions.findByIdAndDocumentId(documentVersionId, document.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document에서 DocumentVersion을 찾을 수 없습니다."));
        if (!Objects.equals(version.getUploadedBy().getId(), requester.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "DocumentVersion의 업로더만 삭제할 수 있습니다.");
        }

        // 변환 작업자가 먼저 PROCESSING으로 바꿨다면 삭제를 거부한다.
        DocumentConversionJob job = conversionJobs.findByDocumentVersionIdForUpdate(documentVersionId)
                .orElseThrow(DocumentVersionDeletionService::inconsistentFiles);
        entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
        if (job.getStatus() == DocumentConversionJobStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PDF 변환 중인 DocumentVersion은 변환이 끝난 뒤 삭제할 수 있습니다.");
        }
        DocumentUpload upload = uploads.findByIdForUpdate(job.getDocumentUpload().getId())
                .orElseThrow(DocumentVersionDeletionService::inconsistentFiles);
        entityManager.refresh(upload, LockModeType.PESSIMISTIC_WRITE);
        List<StoreFile> targetFiles = validateFiles(workspaceId, version, upload, job);
        List<S3ObjectDeletionTask> tasks = targetFiles.stream()
                .map(file -> new S3ObjectDeletionTask(file.getObjectKey(), upload.getExpiresAt()))
                .toList();

        // FK를 참조하는 행부터 삭제하며 어느 단계에서 실패해도 전체를 롤백한다.
        feedbacks.deleteAllForDocumentVersion(documentVersionId);
        conversionJobs.delete(job);
        conversionJobs.flush();
        uploads.delete(upload);
        uploads.flush();
        versions.delete(version);
        versions.flush();
        files.deleteAll(targetFiles);
        files.flush();

        // 외부 S3 요청은 하지 않는다. DB 삭제와 S3 삭제 작업 등록을 함께 커밋한다.
        deletionTasks.saveAllAndFlush(tasks);
    }

    private List<StoreFile> validateFiles(Long workspaceId, DocumentVersion version,
                                           DocumentUpload upload, DocumentConversionJob job) {
        StoreFile original = upload.getOriginalFile();
        StoreFile pdf = upload.getReviewPdfFile();
        if (upload.getStatus() != DocumentUploadStatus.COMPLETED || upload.getDocument() == null
                || !Objects.equals(upload.getDocument().getId(), version.getDocument().getId())
                || !Objects.equals(upload.getWorkspace().getId(), workspaceId)
                || !Objects.equals(upload.getCreatedBy().getId(), version.getUploadedBy().getId())
                || original == null || !Objects.equals(original.getObjectKey(), version.getStorageKey())
                || !DOCX_MEDIA_TYPE.equals(original.getMediaType())
                || (job.getStatus() == DocumentConversionJobStatus.COMPLETED && pdf == null)) {
            throw inconsistentFiles();
        }

        validateFile(workspaceId, version, upload, original, "uploads/");
        List<StoreFile> targetFiles = new ArrayList<>();
        targetFiles.add(original);
        if (pdf != null) {
            if (!"application/pdf".equals(pdf.getMediaType())
                    || Objects.equals(original.getId(), pdf.getId())) {
                throw inconsistentFiles();
            }
            validateFile(workspaceId, version, upload, pdf, "review-pdfs/");
            targetFiles.add(pdf);
        }
        return targetFiles;
    }

    private void validateFile(Long workspaceId, DocumentVersion version, DocumentUpload upload,
                              StoreFile file, String folder) {
        if (!Objects.equals(file.getWorkspace().getId(), workspaceId)
                || !file.getObjectKey().startsWith("workspaces/" + workspaceId + "/" + folder)
                || uploads.existsOtherFileReference(file.getId(), upload.getId())
                || versions.existsByStorageKeyAndIdNot(file.getObjectKey(), version.getId())) {
            throw inconsistentFiles();
        }
    }

    private static void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + "는 양수여야 합니다.");
        }
    }

    private static ResponseStatusException inconsistentFiles() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "DocumentVersion의 DocumentUpload 또는 StoreFile 연결을 확인할 수 없습니다.");
    }
}
