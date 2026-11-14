package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionListResponse;
import withoutjjin.withoutjjin.domain.document.dto.PdfUrlResponse;
import withoutjjin.withoutjjin.domain.document.dto.OriginalUrlResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;
import java.util.function.Supplier;

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
    public List<DocumentVersionListResponse> getVersions(Long workspaceId, Long documentId,
                                                         String issuer, String subject) {
        workspaceService.getWorkSpaceForMember(workspaceId, issuer, subject);
        if (!documentRepository.existsByIdAndWorkspaceId(documentId, workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스에서 문서를 찾을 수 없습니다.");
        }
        return documentVersionRepository.findAllWithConversionStatusByDocumentId(documentId);
    }

    @Transactional(readOnly = true)
    public PdfUrlResponse getPdfUrl(Long documentId, String issuer, String subject) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."));
        workspaceService.getWorkSpaceForMember(document.getWorkspace().getId(), issuer, subject);
        DocumentVersion version = documentVersionRepository.findByDocumentIdAndVersionNumber(documentId, document.getRevision())
                .orElseThrow(DocumentQueryService::pdfNotReady);
        return generatePdfUrl(version);
    }

    @Transactional(readOnly = true)
    public PdfUrlResponse getVersionPdfUrl(Long workspaceId, Long documentId, Long documentVersionId,
                                           String issuer, String subject) {
        DocumentVersion version = findAuthorizedVersion(workspaceId, documentId, documentVersionId, issuer, subject);
        return generatePdfUrl(version);
    }

    @Transactional(readOnly = true)
    public OriginalUrlResponse getVersionOriginalUrl(Long workspaceId, Long documentId, Long documentVersionId,
                                                      String issuer, String subject) {
        DocumentVersion version = findAuthorizedVersion(workspaceId, documentId, documentVersionId, issuer, subject);
        DocumentConversionJob job = findConsistentJob(version, DocumentQueryService::originalNotReady);
        StoreFile original = job.getDocumentUpload().getOriginalFile();
        if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(original.getMediaType())) {
            throw originalNotReady();
        }
        var generated = downloadUrlGenerator.generateOriginal(original.getObjectKey(), version.getOriginalFileName());
        return new OriginalUrlResponse(version.getId(), version.getVersionNumber(), version.getOriginalFileName(),
                generated.downloadUrl(), generated.expiresAt());
    }

    private DocumentVersion findAuthorizedVersion(Long workspaceId, Long documentId, Long documentVersionId,
                                                   String issuer, String subject) {
        workspaceService.getWorkSpaceForMember(workspaceId, issuer, subject);
        if (!documentRepository.existsByIdAndWorkspaceId(documentId, workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스에서 문서를 찾을 수 없습니다.");
        }
        return documentVersionRepository.findByIdAndDocumentId(documentVersionId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "문서 버전을 찾을 수 없습니다."));
    }

    private PdfUrlResponse generatePdfUrl(DocumentVersion version) {
        DocumentConversionJob job = findConsistentJob(version, DocumentQueryService::pdfNotReady);
        if (job.getStatus() != DocumentConversionJobStatus.COMPLETED) {
            throw pdfNotReady();
        }
        StoreFile pdf = job.getDocumentUpload().getReviewPdfFile();
        if (pdf == null || !"application/pdf".equals(pdf.getMediaType())
                || !version.getDocument().getWorkspace().getId().equals(pdf.getWorkspace().getId())) {
            throw pdfNotReady();
        }
        var generated = downloadUrlGenerator.generatePdf(pdf.getObjectKey());
        return new PdfUrlResponse(version.getId(), version.getVersionNumber(), generated.downloadUrl(), generated.expiresAt());
    }

    private DocumentConversionJob findConsistentJob(DocumentVersion version,
                                                   Supplier<ResponseStatusException> notReady) {
        DocumentConversionJob job = conversionJobRepository.findByDocumentVersionId(version.getId())
                .orElseThrow(notReady);
        var upload = job.getDocumentUpload();
        StoreFile original = upload.getOriginalFile();
        Long workspaceId = version.getDocument().getWorkspace().getId();
        if (upload.getStatus() != DocumentUploadStatus.COMPLETED || upload.getDocument() == null
                || !version.getDocument().getId().equals(upload.getDocument().getId())
                || !workspaceId.equals(upload.getWorkspace().getId())
                || original == null || !workspaceId.equals(original.getWorkspace().getId())
                || !version.getStorageKey().equals(original.getObjectKey())) {
            throw notReady.get();
        }
        return job;
    }

    private static ResponseStatusException pdfNotReady() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "해당 버전의 PDF가 아직 준비되지 않았습니다.");
    }

    private static ResponseStatusException originalNotReady() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "해당 버전의 원본 파일이 아직 준비되지 않았습니다.");
    }

}
