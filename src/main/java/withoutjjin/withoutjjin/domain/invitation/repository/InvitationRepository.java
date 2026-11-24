package withoutjjin.withoutjjin.domain.invitation.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {
    Optional<Invitation> findByTokenHash(String tokenHash);

    // 엔티티를 미리 로딩하지 않고 Workspace 잠금에 필요한 ID만 조회한다.
    @Query("select i.workspace.id from Invitation i where i.tokenHash = :tokenHash")
    Optional<Long> findWorkspaceIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id and i.workspace.id = :workspaceId")
    Optional<Invitation> findByIdAndWorkspaceIdForUpdate(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId);

    List<Invitation> findAllByWorkspaceIdOrderByCreatedAtDescIdDesc(Long workspaceId);
}
