package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import java.time.LocalDateTime;

public record InvitationStatusResponse(Long id, Long workspaceId, Long invitedBy,
                                       InvitationStatus invitationStatus, LocalDateTime expiresAt,
                                       LocalDateTime createdAt, LocalDateTime revokedAt) {
}
