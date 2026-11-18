package withoutjjin.withoutjjin.domain.document.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;

import java.util.Optional;
import java.util.List;

public interface DocumentConversionJobRepository extends JpaRepository<DocumentConversionJob, Long> {

    Optional<DocumentConversionJob> findByDocumentVersionId(Long documentVersionId);

    Optional<DocumentConversionJob> findByDocumentUploadId(Long documentUploadId);

    @Query("""
            select job.id from DocumentConversionJob job
            where job.status = withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus.PENDING
            order by job.createdAt asc, job.id asc
            """)
    List<Long> findPendingIds(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DocumentConversionJob job where job.id = :jobId")
    Optional<DocumentConversionJob> findByIdForUpdate(@Param("jobId") Long jobId);
}
