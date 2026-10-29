package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;

public record InvitationAcceptanceResponse(
        Long invitationId,
        Long workspaceId,
        String workspaceName,
        InvitationStatus invitationStatus
) {
}
