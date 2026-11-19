package withoutjjin.withoutjjin.domain.feedback.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;

public record CreateDocumentFeedbackRequest(
        @NotNull(message = "페이지 번호는 필수입니다.")
        @Positive(message = "페이지 번호는 1 이상이어야 합니다.")
        Integer pageNumber,

        @NotNull(message = "x 좌표는 필수입니다.")
        @DecimalMin(value = "0", message = "x 좌표는 0 이상이어야 합니다.")
        @DecimalMax(value = "1", message = "x 좌표는 1 이하여야 합니다.")
        Double x,

        @NotNull(message = "y 좌표는 필수입니다.")
        @DecimalMin(value = "0", message = "y 좌표는 0 이상이어야 합니다.")
        @DecimalMax(value = "1", message = "y 좌표는 1 이하여야 합니다.")
        Double y,

        @NotNull(message = "영역 너비는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "영역 너비는 0보다 커야 합니다.")
        @DecimalMax(value = "1", message = "영역 너비는 1 이하여야 합니다.")
        Double width,

        @NotNull(message = "영역 높이는 필수입니다.")
        @DecimalMin(value = "0", inclusive = false, message = "영역 높이는 0보다 커야 합니다.")
        @DecimalMax(value = "1", message = "영역 높이는 1 이하여야 합니다.")
        Double height,

        @NotBlank(message = "영역 색상은 필수입니다.")
        @Pattern(regexp = "green|yellow|blue|pink", message = "지원하지 않는 영역 색상입니다.")
        String color,

        @NotBlank(message = "피드백 내용은 필수입니다.")
        @Size(max = DocumentFeedback.MAX_CONTENT_LENGTH, message = "피드백 내용은 1000자 이하여야 합니다.")
        String content
) {
}
