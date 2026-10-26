package withoutjjin.withoutjjin.domain.workspace.dto;

import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;

public record WorkspaceResponse(
        Long id,
        String name,
        Long createdBy,
        LocalDateTime createdAt
) {
}
