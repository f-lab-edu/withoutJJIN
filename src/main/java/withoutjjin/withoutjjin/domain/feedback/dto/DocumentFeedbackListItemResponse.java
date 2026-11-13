package withoutjjin.withoutjjin.domain.feedback.dto;

import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

import java.time.LocalDateTime;

public record DocumentFeedbackListItemResponse(
        Long id,
        Long documentVersionId,
        Long createdBy,
        String createdByDisplayName,
        Integer pageNumber,
        Double x,
        Double y,
        Double width,
        Double height,
        String color,
        String content,
        LocalDateTime createdAt
) {
    public static DocumentFeedbackListItemResponse from(DocumentFeedback feedback) {
        return new DocumentFeedbackListItemResponse(
                feedback.getId(), feedback.getDocumentVersion().getId(), feedback.getCreatedBy().getId(),
                feedback.getCreatedBy().getDisplayName(), feedback.getPageNumber(),
                feedback.getX(), feedback.getY(), feedback.getWidth(), feedback.getHeight(),
                feedback.getColor().getValue(), feedback.getContent(), feedback.getCreatedAt());
    }
}
