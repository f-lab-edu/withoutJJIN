package withoutjjin.withoutjjin.domain.workspace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMemberBan;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberBanRepository extends JpaRepository<WorkspaceMemberBan, Long> {
    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    Optional<WorkspaceMemberBan> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    @Query("""
            select b from WorkspaceMemberBan b
            join fetch b.user
            join fetch b.bannedBy
            where b.workspace.id = :workspaceId
            order by b.createdAt desc, b.id desc
            """)
    List<WorkspaceMemberBan> findAllWithUsersByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
