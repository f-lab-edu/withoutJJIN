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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title;

    // 파일을 아직 등록하지 않은 문서는 0, 첫 버전을 등록하면 1이다.
    @Column(name = "revision", nullable = false)
    private Integer revision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    public Document(String title, Integer revision, AppUser createdBy,
                    AppUser approver, Workspace workspace) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("문서 제목은 비어 있을 수 없습니다.");
        }
        if (revision == null || revision < 0) {
            throw new IllegalArgumentException("최신 버전 번호는 0 이상이어야 합니다.");
        }
        if (createdBy == null || workspace == null) {
            throw new IllegalArgumentException("문서 생성자와 워크스페이스가 필요합니다.");
        }
        this.title = title;
        this.revision = revision;
        this.createdBy = createdBy;
        this.approver = approver;
        this.workspace = workspace;
    }

    // 새 버전 저장과 이 메서드 호출은 서비스의 같은 트랜잭션에서 처리한다.
    public void updateRevision(int revision) {
        if (revision <= this.revision) {
            throw new IllegalArgumentException("새 버전 번호는 최신 버전 번호보다 커야 합니다.");
        }
        this.revision = revision;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
