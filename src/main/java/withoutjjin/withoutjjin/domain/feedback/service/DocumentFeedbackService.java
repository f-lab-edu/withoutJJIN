package withoutjjin.withoutjjin.domain.feedback.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.feedback.dto.CreateDocumentFeedbackRequest;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackResponse;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

@Service
@RequiredArgsConstructor
public class DocumentFeedbackService {

    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentConversionJobRepository documentConversionJobRepository;
    private final DocumentFeedbackRepository documentFeedbackRepository;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;

    @Transactional
    public DocumentFeedbackResponse createFeedback(Long documentVersionId, CreateDocumentFeedbackRequest request,
                                                   String issuer, String subject) {
        if (documentVersionId == null || documentVersionId <= 0 || request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DocumentVersion ID와 피드백 요청이 필요합니다.");
        }
        AppUser author = appUserService.getByIssuerAndSubject(issuer, subject);
        DocumentVersion version = documentVersionRepository.findById(documentVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "문서 버전을 찾을 수 없습니다."));
        workspaceService.getWorkSpaceForMember(version.getDocument().getWorkspace().getId(), author);
        requireCompletedPdf(version);

        DocumentFeedback feedback;
        try {
            feedback = new DocumentFeedback(version, author, request.pageNumber(),
                    request.x(), request.y(), request.width(), request.height(),
                    FeedbackColor.fromValue(request.color()), request.content());
        } catch (IllegalArgumentException exception) {
            // 입력값 검증 실패만 400으로 변환한다. DB 저장 실패는 이 catch에서 처리하지 않는다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        DocumentFeedback saved = documentFeedbackRepository.save(feedback);
        return DocumentFeedbackResponse.from(saved);
    }

    private void requireCompletedPdf(DocumentVersion version) {
        DocumentConversionJob job = documentConversionJobRepository.findByDocumentVersionId(version.getId())
                .orElseThrow(DocumentFeedbackService::pdfNotReady);
        if (job.getStatus() != DocumentConversionJobStatus.COMPLETED
                || version.getPageCount() == null || version.getPageCount() < 1) {
            throw pdfNotReady();
        }
        StoreFile pdf = job.getDocumentUpload().getReviewPdfFile();
        if (pdf == null || !"application/pdf".equals(pdf.getMediaType())
                || !version.getDocument().getWorkspace().getId().equals(pdf.getWorkspace().getId())) {
            throw pdfNotReady();
        }
    }

    private static ResponseStatusException pdfNotReady() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이 문서 버전의 PDF가 아직 준비되지 않았습니다.");
    }
}
