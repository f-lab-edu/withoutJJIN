package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.dto.PdfUrlResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentConversionJobRepository conversionJobRepository;
    private final S3DownloadUrlGenerator downloadUrlGenerator;

    @Transactional(readOnly = true)
    public List<DocumentListResponse> getDocuments(Long workspaceId, String issuer, String subject) {
        workspaceService.getWorkSpaceForMember(workspaceId, issuer, subject);
        return documentRepository.findAllWithLatestConversionStatusByWorkspaceId(workspaceId);
    }

    @Transactional(readOnly = true)
    public PdfUrlResponse getPdfUrl(Long documentId, String issuer, String subject) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."));
        workspaceService.getWorkSpaceForMember(document.getWorkspace().getId(), issuer, subject);
        var version = documentVersionRepository.findByDocumentIdAndVersionNumber(documentId, document.getRevision())
                .orElseThrow(DocumentQueryService::pdfNotReady);
        DocumentConversionJob job = conversionJobRepository.findByDocumentVersionId(version.getId())
                .orElseThrow(DocumentQueryService::pdfNotReady);
        if (job.getStatus() != DocumentConversionJobStatus.COMPLETED) {
            throw pdfNotReady();
        }
        StoreFile pdf = job.getDocumentUpload().getReviewPdfFile();
        if (pdf == null || !"application/pdf".equals(pdf.getMediaType())
                || !document.getWorkspace().getId().equals(pdf.getWorkspace().getId())) {
            throw pdfNotReady();
        }
        var generated = downloadUrlGenerator.generatePdf(pdf.getObjectKey());
        return new PdfUrlResponse(generated.downloadUrl(), generated.expiresAt());
    }

    private static ResponseStatusException pdfNotReady() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "최신 버전의 PDF가 아직 준비되지 않았습니다.");
    }
}
