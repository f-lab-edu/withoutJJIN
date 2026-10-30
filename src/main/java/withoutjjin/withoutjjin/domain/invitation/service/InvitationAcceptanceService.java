package withoutjjin.withoutjjin.domain.invitation.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationAcceptanceResponse;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationPreviewResponse;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class InvitationAcceptanceService {

    private final InvitationRepository invitations;
    private final InvitationTokenGenerator tokens;
    private final AppUserRepository users;
    private final WorkspaceMemberRepository members;
    private final Clock clock;

    public InvitationAcceptanceService(
            InvitationRepository invitations, InvitationTokenGenerator tokens,
            AppUserRepository users, WorkspaceMemberRepository members,
            @Qualifier("invitationMailClock") Clock clock
    ) {
        this.invitations = invitations;
        this.tokens = tokens;
        this.users = users;
        this.members = members;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvitationPreviewResponse previewInvitation(String token) {
        Invitation invitation = invitations.findByTokenHash(tokens.hashToken(token))
                .orElseThrow(this::notFound);
        validatePending(invitation, LocalDateTime.now(clock));
        return new InvitationPreviewResponse(
                invitation.getWorkspace().getId(),
                invitation.getWorkspace().getName(),
                invitation.getInvitationStatus(),
                invitation.getExpiresAt()
        );
    }

    @Transactional
    public InvitationAcceptanceResponse acceptInvitation(
            String token, String issuer, String subject, String authenticatedEmail, boolean emailVerified
    ) {
        if (!emailVerified ||
                authenticatedEmail == null ||
                authenticatedEmail.isBlank())
        {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "인증된 이메일이 있는 계정으로 로그인해 주세요.");
        }

        Invitation invitation = invitations.findByTokenHashForUpdate(tokens.hashToken(token))
                .orElseThrow(this::notFound);

        if (!invitation.getTargetEmail().equalsIgnoreCase(authenticatedEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "초대받은 이메일의 Google 계정으로 로그인해 주세요.");
        }

        // 초대 행에 이어 회원 행을 잠근다. 서로 다른 초대라도 같은 회원의 동시 가입을 직렬화한다.
        // 첫 일반 조회(멤버 존재 확인) 전에 잠금을 얻어 MySQL의 이전 조회 시점에 의존하지 않는다.
        AppUser user = users.findByIssuerAndSubjectForUpdate(issuer, subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "로그인 회원을 찾을 수 없습니다."));
        Workspace workspace = invitation.getWorkspace();
        boolean alreadyMember = members.existsByWorkspaceIdAndUserId(workspace.getId(), user.getId());

        if (invitation.getInvitationStatus() == InvitationStatus.ACCEPTED) {
            if (alreadyMember) {
                return response(invitation); // 같은 수락 요청을 다시 보내도 성공 결과를 반환한다.
            }
            // 탈퇴·제외된 회원이 사용한 초대 링크로 다시 가입하지 못하게 한다.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 초대입니다. 새로운 초대를 요청해 주세요.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        validatePending(invitation, now);
        invitation.accept(now);
        if (!alreadyMember) {
            members.saveAndFlush(new WorkspaceMember(workspace, user));
        }
        // 멤버 등록이 실패하면 위의 초대 상태 변경도 같은 트랜잭션에서 롤백된다.
        return response(invitation);
    }

    private void validatePending(Invitation invitation, LocalDateTime now) {
        if (invitation.getInvitationStatus() == InvitationStatus.CANCELED) {
            throw new ResponseStatusException(HttpStatus.GONE, "취소된 초대입니다.");
        }
        if (invitation.getInvitationStatus() == InvitationStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 수락한 초대입니다.");
        }
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "만료된 초대입니다.");
        }
    }

    private InvitationAcceptanceResponse response(Invitation invitation) {
        return new InvitationAcceptanceResponse(invitation.getId(), invitation.getWorkspace().getId(),
                invitation.getWorkspace().getName(), invitation.getInvitationStatus());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "초대를 찾을 수 없습니다.");
    }
}
