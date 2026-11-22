package withoutjjin.withoutjjin.domain.document.dto;

import withoutjjin.withoutjjin.domain.document.entity.DocumentVersionReview;

import java.time.LocalDateTime;

public record DocumentVersionReviewResponse(
        Long id,
        Long documentVersionId,
        Long reviewerId,
        String reviewerDisplayName,
        LocalDateTime completedAt
) {
    public static DocumentVersionReviewResponse from(DocumentVersionReview review) {
        return new DocumentVersionReviewResponse(review.getId(), review.getDocumentVersion().getId(),
                review.getReviewer().getId(), review.getReviewer().getDisplayName(), review.getCompletedAt());
    }
}
