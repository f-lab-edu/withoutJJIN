package withoutjjin.withoutjjin.domain.invitation.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.invitation.dto.CreateRequestInvitation;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationStatusResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationPreviewResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationAcceptanceResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.AcceptInvitationRequest;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationService;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationAcceptanceService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;
    private final InvitationAcceptanceService invitationAcceptanceService;

    // 링크를 가진 사람에게만 최소한의 초대 정보를 보여 준다. 이메일과 토큰은 반환하지 않는다.
    @GetMapping("/invitations") //토큰으로 초대를 조회하고 유효한 초대 정보 반환

    public ResponseEntity<InvitationPreviewResponse> previewInvitation(
            @RequestParam @NotBlank @Size(max = 256) String token
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationAcceptanceService.previewInvitation(token));
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<InvitationAcceptanceResponse> acceptInvitation(
            @AuthenticationPrincipal OidcUser oidcUser,
            @Valid @RequestBody AcceptInvitationRequest request
    ) {
        // 이메일은 요청 본문에서 받지 않고, Spring Security가 검증한 로그인 정보에서 가져온다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationAcceptanceService.acceptInvitation(
                        request.token(), oidcUser.getIssuer().toString(), oidcUser.getSubject(),
                        oidcUser.getEmail(), Boolean.TRUE.equals(oidcUser.getEmailVerified())
                ));
    }



    @GetMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<InvitationStatusResponse> getInvitationStatus( // 초대 상태 조회
            @AuthenticationPrincipal OidcUser oidcUser,
            @PathVariable Long workspaceId,
            @PathVariable Long invitationId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(invitationService.getInvitationStatus(workspaceId, invitationId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationResponse> createInvitation( // 초대 생성
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
