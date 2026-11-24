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
import java.time.temporal.ChronoUnit;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "document_version_review", uniqueConstraints =
        @UniqueConstraint(name = "uk_version_review_version_reviewer",
                columnNames = {"document_version_id", "reviewer_id"}))
public class DocumentVersionReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_version_id", nullable = false, updatable = false)
    private DocumentVersion documentVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false, updatable = false)
    private AppUser reviewer;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private LocalDateTime completedAt;

    public DocumentVersionReview(DocumentVersion documentVersion, AppUser reviewer) {
        if (documentVersion == null || reviewer == null) {
            throw new IllegalArgumentException("DocumentVersion과 피드백을 완료한 AppUser가 필요합니다.");
        }
        if (documentVersion.getPageCount() == null || documentVersion.getPageCount() < 1) {
            throw new IllegalArgumentException("PDF 페이지 수가 확인된 DocumentVersion이 필요합니다.");
        }
        // 한 행은 해당 회원의 피드백 완료를 뜻한다. 워크스페이스 소속과 변환 상태는 서비스에서 확인한다.
        this.documentVersion = documentVersion;
        this.reviewer = reviewer;
    }

    @PrePersist
    private void onCreate() {
        this.completedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.MICROS);
    }
}
