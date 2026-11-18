package withoutjjin.withoutjjin.domain.document.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    boolean existsByIdAndWorkspaceId(Long documentId, Long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select doc from Document doc where doc.id = :documentId")
    Optional<Document> findByIdForUpdate(@Param("documentId") Long documentId);

    // 남은 최대 버전 번호를 조회한다. Document.revision은 마지막 발급 번호이므로 사용하지 않는다.
    @Query("""
            select new withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse(
                doc.id, doc.title, coalesce(latest.versionNumber, 0), doc.createdAt, job.status, count(history.id)
            )
            from Document doc
            left join DocumentVersion history on history.document = doc
            left join DocumentVersion latest
                on latest.document = doc and latest.versionNumber = (
                    select max(remaining.versionNumber) from DocumentVersion remaining
                    where remaining.document = doc
                )
            left join DocumentConversionJob job on job.documentVersion = latest
            where doc.workspace.id = :workspaceId
            group by doc.id, doc.title, latest.versionNumber, doc.createdAt, job.status
            order by doc.createdAt desc, doc.id desc
            """)
    List<DocumentListResponse> findAllWithLatestConversionStatusByWorkspaceId(
            @Param("workspaceId") Long workspaceId);



}
