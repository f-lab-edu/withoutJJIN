package withoutjjin.withoutjjin.domain.invitation.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.invitation.dto.CreateRequestInvitation;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationResponse;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationResponse> createInvitation(
            @AuthenticationPrincipal OidcUser oidcUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody CreateRequestInvitation request
    ) {
        Invitation invitation = invitationService.createInvitation(
                request.targetEmail(),
                workspaceId,
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject()
        );

        // 엔티티 전체 대신 응답에 필요한 값만 반환한다. 토큰과 해시는 제외한다.
        InvitationResponse response = new InvitationResponse(
                invitation.getId(),
                invitation.getWorkspace().getId(),
                invitation.getInvitedBy().getId(),
                invitation.getTargetEmail(),
                invitation.getInvitationStatus(),
                invitation.getEmailSendStatus(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
