package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "document_conversion_job",
        indexes = @Index(name = "idx_conversion_job_status_created_id", columnList = "status, created_at, id"),
        uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversion_job_document_upload", columnNames = "document_upload_id"),
        @UniqueConstraint(name = "uk_conversion_job_document_version", columnNames = "document_version_id")
})
public class DocumentConversionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_upload_id", nullable = false, updatable = false)
    private DocumentUpload documentUpload;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_version_id", nullable = false, updatable = false)
    private DocumentVersion documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private DocumentConversionJobStatus status;

    // 기존 FAILED 행에는 실패 정보가 없으므로 NULL을 허용한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 40)
    private DocumentConversionFailureStage failureStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private DocumentConversionFailureCode failureCode;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public DocumentConversionJob(DocumentUpload documentUpload, DocumentVersion documentVersion) {
        if (documentUpload == null || documentVersion == null) {
            throw new IllegalArgumentException("DocumentUpload와 DocumentVersion이 필요합니다.");
        }
        if (documentUpload.getStatus() != DocumentUploadStatus.COMPLETED
                || documentUpload.getDocument() == null
                || documentUpload.getOriginalFile() == null) {
            throw new IllegalArgumentException("완료된 DocumentUpload만 변환 작업에 연결할 수 있습니다.");
        }

        Document uploadedDocument = documentUpload.getDocument();
        Document versionDocument = documentVersion.getDocument();
        boolean sameDocument = uploadedDocument == versionDocument
                || (uploadedDocument.getId() != null
                && uploadedDocument.getId().equals(versionDocument.getId()));
        if (!sameDocument) {
            throw new IllegalArgumentException("DocumentUpload와 DocumentVersion의 Document가 일치해야 합니다.");
        }
        if (!documentUpload.getOriginalFile().getObjectKey().equals(documentVersion.getStorageKey())) {
            throw new IllegalArgumentException("DocumentUpload.originalFile과 DocumentVersion의 저장 키가 일치해야 합니다.");
        }

        this.documentUpload = documentUpload;
        this.documentVersion = documentVersion;
        this.status = DocumentConversionJobStatus.PENDING;
    }

    public void startProcessing() {
        requireStatus(DocumentConversionJobStatus.PENDING);
        this.status = DocumentConversionJobStatus.PROCESSING;
    }

    public void complete() {
        requireStatus(DocumentConversionJobStatus.PROCESSING);
        this.status = DocumentConversionJobStatus.COMPLETED;
    }

    public void fail() {
        fail(DocumentConversionFailureStage.UNKNOWN, DocumentConversionFailureCode.UNKNOWN);
    }

    public void fail(DocumentConversionFailureStage failureStage, DocumentConversionFailureCode failureCode) {
        requireStatus(DocumentConversionJobStatus.PROCESSING);
        if (failureStage == null || failureCode == null) {
            throw new IllegalArgumentException("실패 단계와 원인 코드가 필요합니다.");
        }
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.failedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        this.status = DocumentConversionJobStatus.FAILED;
    }

    public void retry() {
        requireStatus(DocumentConversionJobStatus.FAILED);
        this.failureStage = null;
        this.failureCode = null;
        this.failedAt = null;
        this.status = DocumentConversionJobStatus.PENDING;
    }

    private void requireStatus(DocumentConversionJobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(expected + " 상태의 DocumentConversionJob만 변경할 수 있습니다.");
        }
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
