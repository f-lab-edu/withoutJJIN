package withoutjjin.withoutjjin.domain.invitation.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationAcceptanceResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationPreviewResponse;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class InvitationAcceptanceService {
    private final InvitationRepository invitations;
    private final InvitationTokenGenerator tokens;
    private final AppUserRepository users;
    private final WorkspaceMemberRepository members;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public InvitationAcceptanceService(InvitationRepository invitations, InvitationTokenGenerator tokens,
                                        AppUserRepository users, WorkspaceMemberRepository members,
                                        WorkspaceRepository workspaces, @Qualifier("invitationClock") Clock clock) {
        this.invitations = invitations;
        this.tokens = tokens;
        this.users = users;
        this.members = members;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvitationPreviewResponse previewInvitation(String token) {
        Invitation invitation = invitations.findByTokenHash(tokens.hashToken(token)).orElseThrow(this::notFound);
        InvitationService.requireActive(invitation, LocalDateTime.now(clock));
        return new InvitationPreviewResponse(invitation.getWorkspace().getId(), invitation.getWorkspace().getName(),
                invitation.getInvitationStatus(), invitation.getExpiresAt());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationAcceptanceResponse acceptInvitation(String token, String issuer, String subject) {
        String hash = tokens.hashToken(token);
        Long workspaceId = invitations.findWorkspaceIdByTokenHash(hash).orElseThrow(this::notFound);
        // 서로 다른 초대 URL로 가입해도 Workspace 행 하나를 먼저 잠가 중복 멤버 등록을 막는다.
        Workspace workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow(this::notFound);
        Invitation invitation = invitations.findByTokenHashForUpdate(hash).orElseThrow(this::notFound);
        InvitationService.requireActive(invitation, LocalDateTime.now(clock));
        AppUser user = users.findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "로그인 회원을 찾을 수 없습니다."));
        if (!members.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            members.saveAndFlush(new WorkspaceMember(workspace, user));
        }
        // 공유 초대는 수락해도 상태·암호문·만료 시각을 변경하지 않는다.
        return new InvitationAcceptanceResponse(invitation.getId(), workspaceId,
                workspace.getName(), invitation.getInvitationStatus());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "초대를 찾을 수 없습니다.");
    }
}
