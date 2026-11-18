package withoutjjin.withoutjjin.domain.document.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface S3ObjectDeletionTaskRepository extends JpaRepository<S3ObjectDeletionTask, Long> {

    @Query("""
            select task.id from S3ObjectDeletionTask task
            where task.nextAttemptAt is null or task.nextAttemptAt <= :now
            order by coalesce(task.nextAttemptAt, task.createdAt) asc, task.id asc
            """)
    List<Long> findReadyIds(@Param("now") LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from S3ObjectDeletionTask task where task.id = :taskId")
    Optional<S3ObjectDeletionTask> findByIdForUpdate(@Param("taskId") Long taskId);
}
