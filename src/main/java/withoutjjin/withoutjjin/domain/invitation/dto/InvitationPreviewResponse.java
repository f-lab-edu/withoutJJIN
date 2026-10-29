package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;

import java.time.LocalDateTime;

public record InvitationPreviewResponse(
        Long workspaceId,
        String workspaceName,
        InvitationStatus invitationStatus,
        LocalDateTime expiresAt
) {
}
