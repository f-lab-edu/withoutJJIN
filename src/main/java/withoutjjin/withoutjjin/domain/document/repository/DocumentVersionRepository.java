package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionListResponse;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByIdAndDocumentId(Long documentVersionId, Long documentId);

    boolean existsByStorageKeyAndIdNot(String storageKey, Long documentVersionId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumberDesc(Long documentId);

    @Query("""
            select new withoutjjin.withoutjjin.domain.document.dto.DocumentVersionListResponse(
                dv.id, dv.document.id, dv.versionNumber, dv.originalFileName, dv.fileSize,
                dv.pageCount, uploader.id, uploader.displayName, dv.createdAt, job.status
            )
            from DocumentVersion dv
            join dv.uploadedBy uploader
            left join DocumentConversionJob job on job.documentVersion = dv
            where dv.document.id = :documentId
            order by dv.versionNumber desc
            """)
    List<DocumentVersionListResponse> findAllWithConversionStatusByDocumentId(
            @Param("documentId") Long documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNumber(
            Long documentId, Integer versionNumber
    );

    List<DocumentVersion> findAllByDocumentIdOrderByVersionNumberDesc(
            Long documentId
    );

}
