package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;

import java.time.LocalDateTime;

public record InvitationResponse(
        Long id,
        Long workspaceId,
        Long invitedBy,
        String targetEmail,
        InvitationStatus invitationStatus,
        EmailSendStatus emailSendStatus,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
