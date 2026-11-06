package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "document_upload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    // 새 문서를 등록하는 작업은 아직 연결된 문서가 없을 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private DocumentUploadStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_file_id", unique = true)
    private StoreFile originalFile;

    // PDF 변환을 마치기 전에는 null이다.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_pdf_file_id", unique = true)
    private StoreFile reviewPdfFile;

    // 용량 예약에 사용할 값이다. 실제 용량 차감 로직은 별도로 구현한다.
    @Column(name = "reserved_bytes", nullable = false)
    private Long reservedBytes;

    // 작업의 유효기한이다. URL 만료 시각과 같은 값으로 쓸지는 서비스에서 정한다.
    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;





    public DocumentUpload(Workspace workspace, Document document, AppUser createdBy,
                          StoreFile originalFile, Long reservedBytes, LocalDateTime expiresAt) {
        if (workspace == null || createdBy == null) {
            throw new IllegalArgumentException("워크스페이스와 업로드 요청자가 필요합니다.");
        }
        if (reservedBytes == null || reservedBytes < 0) {
            throw new IllegalArgumentException("예약 용량은 0 이상이어야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("업로드 작업의 만료 시각이 필요합니다.");
        }
        this.workspace = workspace;
        if (document != null) validateWorkspace(document.getWorkspace());
        if (originalFile != null) validateWorkspace(originalFile.getWorkspace());
        this.document = document;
        this.createdBy = createdBy;
        this.originalFile = originalFile;
        this.reservedBytes = reservedBytes;
        this.expiresAt = expiresAt;
        this.status = DocumentUploadStatus.PENDING;
    }

    private void validateWorkspace(Workspace relatedWorkspace) {
        boolean sameWorkspace = workspace == relatedWorkspace
                || (workspace.getId() != null && relatedWorkspace != null
                && workspace.getId().equals(relatedWorkspace.getId()));
        if (!sameWorkspace) {
            throw new IllegalArgumentException("같은 워크스페이스의 문서와 파일만 연결할 수 있습니다.");
        }
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
