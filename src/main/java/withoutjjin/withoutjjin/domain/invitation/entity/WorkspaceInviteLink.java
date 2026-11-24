package withoutjjin.withoutjjin.domain.invitation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "workspace_invite_link",
        uniqueConstraints = @UniqueConstraint(name = "uk_workspace_invite_link_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "idx_workspace_invite_link_active", columnList = "workspace_id,revoked_at,expires_at"))
public class WorkspaceInviteLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    // 원문 토큰과 이메일 주소는 저장하지 않는다. 여러 회원이 같은 유효한 링크로 참여할 수 있다.
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public WorkspaceInviteLink(Workspace workspace, AppUser createdBy, String tokenHash,
                               LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.workspace = Objects.requireNonNull(workspace, "Workspace가 필요합니다.");
        this.createdBy = Objects.requireNonNull(createdBy, "AppUser가 필요합니다.");
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("초대 URL에는 SHA-256 해시가 필요합니다.");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "생성 시각이 필요합니다.");
        this.expiresAt = Objects.requireNonNull(expiresAt, "만료 시각이 필요합니다.");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("초대 URL 만료 시각은 생성 시각 이후여야 합니다.");
        }
        this.tokenHash = tokenHash;
    }

    public boolean isActiveAt(LocalDateTime now) {
        Objects.requireNonNull(now, "확인 시각이 필요합니다.");
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(LocalDateTime now) {
        Objects.requireNonNull(now, "폐기 시각이 필요합니다.");
        if (revokedAt == null) revokedAt = now;
    }
}
