package withoutjjin.withoutjjin.domain.document.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersionReview;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionReviewRepository extends JpaRepository<DocumentVersionReview, Long> {

    Optional<DocumentVersionReview> findByDocumentVersionIdAndReviewerId(Long documentVersionId, Long reviewerId);

    @EntityGraph(attributePaths = "reviewer")
    List<DocumentVersionReview> findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(Long documentVersionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from DocumentVersionReview review where review.documentVersion.id = :documentVersionId")
    int deleteAllForDocumentVersion(@Param("documentVersionId") Long documentVersionId);
}
