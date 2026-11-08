package withoutjjin.withoutjjin.domain.document.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;

import java.util.Optional;

public interface DocumentConversionJobRepository extends JpaRepository<DocumentConversionJob, Long> {

    Optional<DocumentConversionJob> findByDocumentVersionId(Long documentVersionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DocumentConversionJob job where job.id = :jobId")
    Optional<DocumentConversionJob> findByIdForUpdate(@Param("jobId") Long jobId);
}
