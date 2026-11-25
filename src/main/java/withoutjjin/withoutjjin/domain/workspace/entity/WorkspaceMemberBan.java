package withoutjjin.withoutjjin.domain.workspace.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "workspace_member_ban",
        uniqueConstraints = @UniqueConstraint(name = "uk_workspace_member_ban_workspace_user",
                columnNames = {"workspace_id", "user_id"}))
public class WorkspaceMemberBan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "banned_by", nullable = false, updatable = false)
    private AppUser bannedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WorkspaceMemberBan(Workspace workspace, AppUser user, AppUser bannedBy) {
        this.workspace = Objects.requireNonNull(workspace);
        this.user = Objects.requireNonNull(user);
        this.bannedBy = Objects.requireNonNull(bannedBy);
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
