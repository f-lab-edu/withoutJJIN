package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "document_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_version_document_id_version_number",
                columnNames = {"document_id", "version_number"}
        )
)
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_number", nullable = false, updatable = false)
    private Integer versionNumber;

    @Column(name = "change_summary", nullable = false)
    private String changeSummary;

    // PDF 변환 전에는 페이지 수를 알 수 없으므로 null을 허용한다.
    @Column(name = "page_count")
    private Integer pageCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private Document document;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 1024)
    private String storageKey;

    @Column(name = "original_file_name", nullable = false, updatable = false)
    private String originalFileName;

    @Column(name = "file_size", nullable = false, updatable = false)
    private Long fileSize;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false, updatable = false)
    private AppUser uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public DocumentVersion(Integer versionNumber, String changeSummary,
                           Integer pageCount, Document document, String storageKey,
                           String originalFileName, Long fileSize, AppUser uploadedBy) {
        if (versionNumber == null || versionNumber < 1) {
            throw new IllegalArgumentException("문서 버전 번호는 1 이상이어야 합니다.");
        }
        if (changeSummary == null) {
            throw new IllegalArgumentException("변경 설명은 null일 수 없습니다.");
        }
        if (pageCount != null && pageCount < 1) {
            throw new IllegalArgumentException("페이지 수는 1 이상이어야 합니다.");
        }
        if (document == null || uploadedBy == null) {
            throw new IllegalArgumentException("소속 문서와 업로더가 필요합니다.");
        }
        if (storageKey == null || storageKey.isBlank()
                || originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("파일 저장 키와 원본 파일명이 필요합니다.");
        }
        if (fileSize == null || fileSize < 0) {
            throw new IllegalArgumentException("파일 크기는 0 이상이어야 합니다.");
        }
        this.versionNumber = versionNumber;
        this.changeSummary = changeSummary;
        this.pageCount = pageCount;
        this.document = document;
        this.storageKey = storageKey;
        this.originalFileName = originalFileName;
        this.fileSize = fileSize;
        this.uploadedBy = uploadedBy;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
