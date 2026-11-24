package withoutjjin.withoutjjin.domain.invitation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.invitation.entity.WorkspaceInviteLink;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkspaceInviteLinkRepository extends JpaRepository<WorkspaceInviteLink, Long> {
    Optional<WorkspaceInviteLink> findByTokenHash(String tokenHash);

    // 수락 시 Workspace를 먼저 잠그기 위해 엔티티를 로딩하지 않고 ID만 조회한다.
    @Query("select l.workspace.id from WorkspaceInviteLink l where l.tokenHash = :tokenHash")
    Optional<Long> findWorkspaceIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("""
            select l from WorkspaceInviteLink l
            where l.workspace.id = :workspaceId and l.revokedAt is null and l.expiresAt > :now
            order by l.createdAt desc, l.id desc
            """)
    List<WorkspaceInviteLink> findActiveByWorkspaceId(@Param("workspaceId") Long workspaceId,
                                                   @Param("now") LocalDateTime now);
}
