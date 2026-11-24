package withoutjjin.withoutjjin.domain.workspace.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> findByIdForUpdate(@Param("id") Long id);
}
