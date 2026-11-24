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
@Table(name = "invitation", indexes = @Index(
        name = "idx_invitation_workspace_created", columnList = "workspace_id,created_at,id"))
public class Invitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    // 생성자가 같은 URL을 다시 복사할 수 있도록 암호문만 보관한다.
    @Column(name = "encrypted_token", length = 255)
    private String encryptedToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "invitation_status", nullable = false)
    private InvitationStatus invitationStatus;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by", nullable = false, updatable = false)
    private AppUser invitedBy;

    public Invitation(Workspace workspace, AppUser invitedBy, String tokenHash,
                      String encryptedToken, LocalDateTime createdAt) {
        this.workspace = Objects.requireNonNull(workspace);
        this.invitedBy = Objects.requireNonNull(invitedBy);
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("초대 토큰의 SHA-256 해시가 필요합니다.");
        }
        if (encryptedToken == null || encryptedToken.isBlank() || encryptedToken.length() > 255) {
            throw new IllegalArgumentException("초대 URL 재조회용 토큰 암호문이 필요합니다.");
        }
        this.tokenHash = tokenHash;
        this.encryptedToken = encryptedToken;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = createdAt.plusDays(7);
        this.invitationStatus = InvitationStatus.ACTIVE;
    }

    public boolean isActiveAt(LocalDateTime now) {
        Objects.requireNonNull(now);
        return invitationStatus == InvitationStatus.ACTIVE && revokedAt == null
                && expiresAt.isAfter(now);
    }

    public void revoke(LocalDateTime now) {
        Objects.requireNonNull(now);
        if (revokedAt == null) {
            revokedAt = now;
        }
        invitationStatus = InvitationStatus.CANCELED;
        encryptedToken = null;
    }
}
