package withoutjjin.withoutjjin.domain.feedback.entity;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "document_feedback", indexes = @Index(
        name = "idx_feedback_version_created_id", columnList = "document_version_id, created_at, id"
))
public class DocumentFeedback {

    public static final int MAX_CONTENT_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_version_id", nullable = false, updatable = false)
    private DocumentVersion documentVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "page_number", nullable = false, updatable = false)
    private Integer pageNumber;

    // PDF 페이지 왼쪽 위 기준의 0~1 비율이다. 화면 픽셀 수와 무관하다.
    @Column(name = "x", nullable = false, updatable = false)
    private Double x;

    @Column(name = "y", nullable = false, updatable = false)
    private Double y;

    @Column(name = "width", nullable = false, updatable = false)
    private Double width;

    @Column(name = "height", nullable = false, updatable = false)
    private Double height;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", nullable = false, length = 20)
    private FeedbackColor color;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public DocumentFeedback(DocumentVersion documentVersion, AppUser createdBy,
                            Integer pageNumber, Double x, Double y, Double width, Double height,
                            FeedbackColor color, String content) {
        if (documentVersion == null || createdBy == null) {
            throw new IllegalArgumentException("DocumentVersion과 작성자 AppUser가 필요합니다.");
        }
        Integer pageCount = documentVersion.getPageCount();
        if (pageCount == null || pageCount < 1) {
            throw new IllegalArgumentException("페이지 수가 확인된 DocumentVersion이 필요합니다.");
        }
        if (pageNumber == null || pageNumber < 1 || pageNumber > pageCount) {
            throw new IllegalArgumentException("페이지 번호는 DocumentVersion의 페이지 범위 안이어야 합니다.");
        }
        validateRegion(x, y, width, height);
        if (color == null) {
            throw new IllegalArgumentException("피드백 영역 색상이 필요합니다.");
        }
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("피드백 내용은 비어 있을 수 없으며 1000자 이하여야 합니다.");
        }
        this.documentVersion = documentVersion;
        this.createdBy = createdBy;
        this.pageNumber = pageNumber;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
        this.content = content;
    }

    private static void validateRegion(Double x, Double y, Double width, Double height) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(width) || !isFinite(height)) {
            throw new IllegalArgumentException("영역 좌표와 크기는 유한한 숫자여야 합니다.");
        }
        if (x < 0 || x > 1 || y < 0 || y > 1
                || width <= 0 || width > 1 || height <= 0 || height > 1
                || x + width > 1 || y + height > 1) {
            throw new IllegalArgumentException("영역 좌표는 0~1 비율이며 선택 영역이 페이지 경계를 넘을 수 없습니다.");
        }
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
