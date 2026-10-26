package withoutjjin.withoutjjin.domain.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(

        @NotBlank(message = "워크스페이스 이름은 필수입니다.")
        @Size(max = 80, message = "워크스페이스 이름은 80자 이하여야합니다.")
        String name
        ) {
}
