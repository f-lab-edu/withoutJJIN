package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    // 최신 버전에 작업이 없어도 Document를 누락하지 않고 DTO 필드만 조회한다.
    @Query("""
            select new withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse(
                doc.id, doc.title, doc.revision, doc.createdAt, job.status
            )
            from Document doc
            left join DocumentVersion latest
                on latest.document = doc and latest.versionNumber = doc.revision
            left join DocumentConversionJob job on job.documentVersion = latest
            where doc.workspace.id = :workspaceId
            order by doc.createdAt desc, doc.id desc
            """)
    List<DocumentListResponse> findAllWithLatestConversionStatusByWorkspaceId(
            @Param("workspaceId") Long workspaceId);



}
