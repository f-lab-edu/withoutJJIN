package withoutjjin.withoutjjin.domain.document.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;

import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DocumentConversionTransactions {

    private final DocumentConversionJobRepository jobs;
    private final StoreFileRepository files;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<PreparedConversion> claim(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("DocumentConversionJob 번호는 양수여야 합니다.");
        }
        Optional<DocumentConversionJob> found = jobs.findByIdForUpdate(jobId);
        if (found.isEmpty() || found.get().getStatus() != DocumentConversionJobStatus.PENDING) {
            return Optional.empty();
        }

        DocumentConversionJob job = found.get();
        DocumentVersion version = job.getDocumentVersion();
        StoreFile original = job.getDocumentUpload().getOriginalFile();
        job.startProcessing();
        // 엔티티 대신 변환 요청에 필요한 값만 반환한다. 메서드가 반환된 뒤 트랜잭션이 커밋된다.
        return Optional.of(new PreparedConversion(job.getId(), version.getDocument().getWorkspace().getId(),
                version.getId(), original == null ? null : original.getId(), version.getStorageKey()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void complete(PreparedConversion prepared, StoredPdf pdf) {
        DocumentConversionJob job = jobs.findByIdForUpdate(prepared.jobId())
                .orElseThrow(() -> new IllegalStateException("DocumentConversionJob을 찾을 수 없습니다."));
        if (job.getStatus() != DocumentConversionJobStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태의 DocumentConversionJob만 완료할 수 있습니다.");
        }
        validateSource(job, prepared);
        if (pdf == null || pdf.objectKey() == null
                || !pdf.objectKey().startsWith("workspaces/" + prepared.workspaceId() + "/review-pdfs/")) {
            throw new IllegalArgumentException("변환한 PDF의 S3 저장 키를 확인해야 합니다.");
        }

        DocumentUpload upload = job.getDocumentUpload();
        DocumentVersion version = job.getDocumentVersion();
        StoreFile pdfFile = files.save(new StoreFile(upload.getWorkspace(), pdf.objectKey(),
                pdfFileName(version.getOriginalFileName()), "application/pdf", pdf.sizeBytes()));
        upload.attachReviewPdf(pdfFile);
        version.recordPageCount(pdf.pageCount());
        job.complete();
        // PDF 연결·페이지 수·완료 상태의 DB 제약을 같은 트랜잭션에서 확인한다.
        jobs.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void markFailed(Long jobId) {
        jobs.findByIdForUpdate(jobId)
                .filter(job -> job.getStatus() == DocumentConversionJobStatus.PROCESSING)
                .ifPresent(DocumentConversionJob::fail);
    }

    private void validateSource(DocumentConversionJob job, PreparedConversion prepared) {
        DocumentUpload upload = job.getDocumentUpload();
        DocumentVersion version = job.getDocumentVersion();
        StoreFile original = upload.getOriginalFile();
        if (upload.getStatus() != DocumentUploadStatus.COMPLETED || upload.getDocument() == null
                || original == null || !Objects.equals(original.getId(), prepared.originalFileId())
                || !Objects.equals(original.getObjectKey(), prepared.originalObjectKey())
                || !Objects.equals(version.getId(), prepared.documentVersionId())
                || !Objects.equals(version.getStorageKey(), prepared.originalObjectKey())
                || !Objects.equals(upload.getDocument().getId(), version.getDocument().getId())
                || !Objects.equals(upload.getWorkspace().getId(), prepared.workspaceId())
                || !Objects.equals(version.getDocument().getWorkspace().getId(), prepared.workspaceId())) {
            throw new IllegalStateException("변환 중 DocumentUpload 또는 DocumentVersion의 원본 연결이 변경되었습니다.");
        }
    }

    private String pdfFileName(String originalFileName) {
        int extensionStart = originalFileName.lastIndexOf('.');
        String name = extensionStart > 0 ? originalFileName.substring(0, extensionStart) : originalFileName;
        return name + ".pdf";
    }

    public record PreparedConversion(Long jobId, Long workspaceId, Long documentVersionId,
                                     Long originalFileId, String originalObjectKey) {
    }
}
