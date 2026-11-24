package withoutjjin.withoutjjin.domain.invitation.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationStatusResponse;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class InvitationService {
    private final WorkspaceRepository workspaces;
    private final AppUserService users;
    private final WorkspaceMemberRepository members;
    private final InvitationRepository invitations;
    private final InvitationTokenGenerator tokens;
    private final InvitationTokenCipher cipher;
    private final Clock clock;

    public InvitationService(WorkspaceRepository workspaces, AppUserService users,
                             WorkspaceMemberRepository members, InvitationRepository invitations,
                             InvitationTokenGenerator tokens, InvitationTokenCipher cipher,
                             @Qualifier("invitationClock") Clock clock) {
        this.workspaces = workspaces;
        this.users = users;
        this.members = members;
        this.invitations = invitations;
        this.tokens = tokens;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional
    public InvitationResponse createInvitation(Long workspaceId, String issuer, String subject) {
        AppUser creator = users.getByIssuerAndSubject(issuer, subject);
        Workspace workspace = authorizedWorkspace(workspaceId, creator, true);
        String token = tokens.generateToken();
        Invitation invitation = invitations.save(new Invitation(workspace, creator,
                tokens.hashToken(token), cipher.encrypt(token), LocalDateTime.now(clock)));
        return response(invitation, token);
    }

    @Transactional(readOnly = true)
    public List<InvitationStatusResponse> getInvitations(Long workspaceId, String issuer, String subject) {
        authorizedWorkspace(workspaceId, users.getByIssuerAndSubject(issuer, subject), false);
        return invitations.findAllByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId).stream()
                .map(invitation -> new InvitationStatusResponse(invitation.getId(), workspaceId,
                        invitation.getInvitedBy().getId(), invitation.getInvitationStatus(),
                        invitation.getExpiresAt(), invitation.getCreatedAt(), invitation.getRevokedAt()))
                .toList();
    }

    @Transactional
    public InvitationResponse getInvitation(Long workspaceId, Long invitationId, String issuer, String subject) {
        // 폐기와 URL 재조회를 같은 Workspace 잠금 순서로 처리한다.
        authorizedWorkspace(workspaceId, users.getByIssuerAndSubject(issuer, subject), true);
        Invitation invitation = findForUpdate(workspaceId, invitationId);
        requireActive(invitation, LocalDateTime.now(clock));
        String token = cipher.decrypt(invitation.getEncryptedToken());
        if (!tokens.hashToken(token).equals(invitation.getTokenHash())) {
            throw new IllegalStateException("초대 토큰 검증에 실패했습니다.");
        }
        return response(invitation, token);
    }

    @Transactional
    public void revokeInvitation(Long workspaceId, Long invitationId, String issuer, String subject) {
        authorizedWorkspace(workspaceId, users.getByIssuerAndSubject(issuer, subject), true);
        findForUpdate(workspaceId, invitationId).revoke(LocalDateTime.now(clock));
    }

    private Workspace authorizedWorkspace(Long workspaceId, AppUser user, boolean lock) {
        Workspace workspace = (lock ? workspaces.findByIdForUpdate(workspaceId) : workspaces.findById(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));
        if (!workspace.getCreatedBy().getId().equals(user.getId())
                || !members.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 생성자만 초대 URL을 관리할 수 있습니다.");
        }
        return workspace;
    }

    private Invitation findForUpdate(Long workspaceId, Long invitationId) {
        return invitations.findByIdAndWorkspaceIdForUpdate(invitationId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대를 찾을 수 없습니다."));
    }

    static void requireActive(Invitation invitation, LocalDateTime now) {
        if (!invitation.isActiveAt(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "폐기되었거나 만료된 초대 URL입니다.");
        }
    }

    private InvitationResponse response(Invitation invitation, String token) {
        return new InvitationResponse(invitation.getId(), invitation.getWorkspace().getId(),
                invitation.getInvitedBy().getId(), invitation.getInvitationStatus(),
                invitation.getExpiresAt(), invitation.getCreatedAt(), token);
    }
}
