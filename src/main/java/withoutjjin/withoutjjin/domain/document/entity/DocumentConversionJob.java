package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "document_conversion_job", uniqueConstraints = {
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

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
