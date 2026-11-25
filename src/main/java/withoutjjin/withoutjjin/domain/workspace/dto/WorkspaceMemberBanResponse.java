package withoutjjin.withoutjjin.domain.workspace.dto;

import java.time.LocalDateTime;

public record WorkspaceMemberBanResponse(Long userId, String displayName, Long bannedBy,
                                         LocalDateTime createdAt) {
}
