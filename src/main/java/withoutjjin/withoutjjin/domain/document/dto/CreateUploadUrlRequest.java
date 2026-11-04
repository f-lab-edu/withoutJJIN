package withoutjjin.withoutjjin.domain.document.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadUrlRequest(

        @NotBlank(message = "파일 이름은 필수입니다.")
        @Size(max = 255, message = "파일 이름은 255자 이하여야 합니다.")
        String fileName,

        @NotNull(message = "파일 크기는 필수입니다.")  //NULL이면안되는거고
        @Positive(message = "파일 크기는 0보다 커야 합니다.") // 0보다 커야한다는거임
        Long fileSize,

        @NotBlank(message = "파일 형식은 필수입니다.")
        String contentType
) {
}
