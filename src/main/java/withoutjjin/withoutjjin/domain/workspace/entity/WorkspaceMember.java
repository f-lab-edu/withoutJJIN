package withoutjjin.withoutjjin.domain.workspace.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@Table(
        name = "workspace_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workspace_member_workspace_user",
                columnNames = {"workspace_id", "user_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    public WorkspaceMember(Workspace workspace, AppUser user) {
        this.workspace = workspace;
        this.user = user;
    }


    @PrePersist
    private void createJoinAt() {
        this.joinedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }





}
