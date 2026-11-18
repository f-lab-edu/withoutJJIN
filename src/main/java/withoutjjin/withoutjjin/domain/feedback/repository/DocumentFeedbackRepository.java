package withoutjjin.withoutjjin.domain.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

import java.util.List;

public interface DocumentFeedbackRepository extends JpaRepository<DocumentFeedback, Long> {

    @Query("""
            select feedback from DocumentFeedback feedback
            join fetch feedback.createdBy
            where feedback.documentVersion.id = :documentVersionId
            order by feedback.createdAt asc, feedback.id asc
            """)
    List<DocumentFeedback> findAllByDocumentVersionId(@Param("documentVersionId") Long documentVersionId);
}
