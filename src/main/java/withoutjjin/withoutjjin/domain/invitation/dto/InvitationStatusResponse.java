package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;

import java.time.LocalDateTime;

public record InvitationStatusResponse(
        Long id,
        Long workspaceId,
        String targetEmail,
        InvitationStatus invitationStatus,
        EmailSendStatus emailSendStatus,
        Long retryCount,
        LocalDateTime nextRetryAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
