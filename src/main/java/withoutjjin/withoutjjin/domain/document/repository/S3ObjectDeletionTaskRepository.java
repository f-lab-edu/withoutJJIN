package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;

public interface S3ObjectDeletionTaskRepository extends JpaRepository<S3ObjectDeletionTask, Long> {
}
