package withoutjjin.withoutjjin.domain.feedback.dto;

import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

import java.time.LocalDateTime;

public record DocumentFeedbackResponse(
        Long id,
        Long documentVersionId,
        Long createdBy,
        Integer pageNumber,
        Double x,
        Double y,
        Double width,
        Double height,
        String color,
        String content,
        LocalDateTime createdAt
) {
    public static DocumentFeedbackResponse from(DocumentFeedback feedback) {
        return new DocumentFeedbackResponse(
                feedback.getId(), feedback.getDocumentVersion().getId(), feedback.getCreatedBy().getId(),
                feedback.getPageNumber(), feedback.getX(), feedback.getY(), feedback.getWidth(), feedback.getHeight(),
                feedback.getColor().getValue(), feedback.getContent(), feedback.getCreatedAt());
    }
}
