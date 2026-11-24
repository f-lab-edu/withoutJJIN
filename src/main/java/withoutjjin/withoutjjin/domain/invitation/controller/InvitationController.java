package withoutjjin.withoutjjin.domain.invitation.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import withoutjjin.withoutjjin.domain.invitation.dto.*;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationAcceptanceService;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationService;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {
    private final InvitationService invitationService;
    private final InvitationAcceptanceService invitationAcceptanceService;

    @GetMapping("/invitations")
    public ResponseEntity<InvitationPreviewResponse> previewInvitation(
            @RequestParam @NotBlank @Size(max = 256) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationAcceptanceService.previewInvitation(token));
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<InvitationAcceptanceResponse> acceptInvitation(
            @AuthenticationPrincipal OidcUser user, @Valid @RequestBody AcceptInvitationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationAcceptanceService.acceptInvitation(
                        request.token(), user.getIssuer().toString(), user.getSubject()));
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationResponse> createInvitation(
            @AuthenticationPrincipal OidcUser user, @PathVariable @Positive Long workspaceId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(invitationService.createInvitation(workspaceId, user.getIssuer().toString(), user.getSubject()));
    }

    @GetMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<List<InvitationStatusResponse>> getInvitations(
            @AuthenticationPrincipal OidcUser user, @PathVariable @Positive Long workspaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationService.getInvitations(workspaceId, user.getIssuer().toString(), user.getSubject()));
    }

    @GetMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<InvitationResponse> getInvitation(
            @AuthenticationPrincipal OidcUser user, @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long invitationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationService.getInvitation(workspaceId, invitationId,
                        user.getIssuer().toString(), user.getSubject()));
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal OidcUser user, @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long invitationId) {
        invitationService.revokeInvitation(workspaceId, invitationId, user.getIssuer().toString(), user.getSubject());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
