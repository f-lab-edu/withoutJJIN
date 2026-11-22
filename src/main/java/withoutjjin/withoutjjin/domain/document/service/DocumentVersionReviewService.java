package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionReviewResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersionReview;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionReviewRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DocumentVersionReviewService {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentVersionReviewRepository reviews;
    private final DocumentConversionJobRepository conversionJobs;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentVersionReviewResponse completeReview(Long workspaceId, Long documentId, Long documentVersionId,
                                                       String issuer, String subject) {
        AppUser reviewer = requireMember(workspaceId, documentId, documentVersionId, issuer, subject);
        // 버전 삭제와 같은 Document 잠금으로 완료 등록·중복 요청·삭제를 직렬화한다.
        Document document = documents.findByIdForUpdate(documentId)
                .filter(found -> Objects.equals(found.getWorkspace().getId(), workspaceId))
                .orElseThrow(DocumentVersionReviewService::documentNotFound);
        DocumentVersion version = findVersion(documentVersionId, document.getId());
        requireCompletedPdf(version);

        DocumentVersionReview review = reviews.findByDocumentVersionIdAndReviewerId(documentVersionId, reviewer.getId())
                .orElseGet(() -> reviews.saveAndFlush(new DocumentVersionReview(version, reviewer)));
        return DocumentVersionReviewResponse.from(review);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersionReviewResponse> getReviews(Long workspaceId, Long documentId, Long documentVersionId,
                                                        String issuer, String subject) {
        requireMember(workspaceId, documentId, documentVersionId, issuer, subject);
        if (!documents.existsByIdAndWorkspaceId(documentId, workspaceId)) {
            throw documentNotFound();
        }
        findVersion(documentVersionId, documentId);
        return reviews.findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(documentVersionId).stream()
                .map(DocumentVersionReviewResponse::from)
                .toList();
    }

    private AppUser requireMember(Long workspaceId, Long documentId, Long documentVersionId,
                                  String issuer, String subject) {
        requirePositiveId(workspaceId, "workspaceId");
        requirePositiveId(documentId, "documentId");
        requirePositiveId(documentVersionId, "documentVersionId");
        AppUser reviewer = appUserService.getByIssuerAndSubject(issuer, subject);
        workspaceService.getWorkSpaceForMember(workspaceId, reviewer);
        return reviewer;
    }

    private DocumentVersion findVersion(Long documentVersionId, Long documentId) {
        return versions.findByIdAndDocumentId(documentVersionId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document에서 DocumentVersion을 찾을 수 없습니다."));
    }

    private void requireCompletedPdf(DocumentVersion version) {
        DocumentConversionJob job = conversionJobs.findByDocumentVersionId(version.getId())
                .orElseThrow(DocumentVersionReviewService::pdfNotReady);
        if (job.getStatus() != DocumentConversionJobStatus.COMPLETED
                || version.getPageCount() == null || version.getPageCount() < 1) {
            throw pdfNotReady();
        }
        DocumentUpload upload = job.getDocumentUpload();
        StoreFile pdf = upload.getReviewPdfFile();
        if (upload.getStatus() != DocumentUploadStatus.COMPLETED || upload.getDocument() == null
                || !Objects.equals(upload.getDocument().getId(), version.getDocument().getId())
                || !Objects.equals(upload.getWorkspace().getId(), version.getDocument().getWorkspace().getId())
                || pdf == null || !"application/pdf".equals(pdf.getMediaType())
                || !Objects.equals(pdf.getWorkspace().getId(), version.getDocument().getWorkspace().getId())) {
            throw pdfNotReady();
        }
    }

    private static void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + "는 양수여야 합니다.");
        }
    }

    private static ResponseStatusException documentNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스에서 Document를 찾을 수 없습니다.");
    }

    private static ResponseStatusException pdfNotReady() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이 문서 버전의 PDF가 아직 준비되지 않았습니다.");
    }
}
