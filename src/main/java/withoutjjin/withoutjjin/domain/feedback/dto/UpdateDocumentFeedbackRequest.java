package withoutjjin.withoutjjin.domain.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

public record UpdateDocumentFeedbackRequest(
        @NotBlank(message = "피드백 내용은 필수입니다.")
        @Size(max = DocumentFeedback.MAX_CONTENT_LENGTH, message = "피드백 내용은 1000자 이하여야 합니다.")
        String content
) {
}
