package withoutjjin.withoutjjin.domain.document.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;

public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {

    @Query("""
            select count(upload) > 0 from DocumentUpload upload
            where upload.id <> :uploadId
                and (upload.originalFile.id = :fileId or upload.reviewPdfFile.id = :fileId)
            """)
    boolean existsOtherFileReference(@Param("fileId") Long fileId, @Param("uploadId") Long uploadId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select upload from DocumentUpload upload where upload.id = :uploadId")
    Optional<DocumentUpload> findByIdForUpdate(@Param("uploadId") Long uploadId);
}
