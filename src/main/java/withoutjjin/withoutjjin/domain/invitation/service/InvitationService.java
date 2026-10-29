package withoutjjin.withoutjjin.domain.invitation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final long EXPIRATION_HOURS = 24;

    private final WorkspaceRepository workspaceRepository;
    private final AppUserService appUserService;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final InvitationRepository invitationRepository;
    private final InvitationTokenGenerator invitationTokenGenerator;
    private final InvitationTokenCipher invitationTokenCipher;

    @Transactional
    public Invitation createInvitation(
            String targetEmail,
            Long workspaceId,
            String issuer,
            String subject
    ) {
        AppUser inviter = appUserService.getByIssuerAndSubject(issuer, subject);
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "워크스페이스를 찾을 수 없습니다."
                ));

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        validateInvitation(workspaceId, inviter.getId(), targetEmail, now);

        // 링크에 사용할 원문을 만들고, 검증용 해시를 계산한다.
        String token = invitationTokenGenerator.generateToken();// 초대 링크에 들어갈 원문
        String tokenHash = invitationTokenGenerator.hashToken(token);
        // SHA-256으로 해시를 계산해 DB에 저장해.이유 : 어떤 초대 링크인지 확인을위해서 저장 .
        String encryptedToken = invitationTokenCipher.encrypt(token); //  난수를 암호화한 녀석
        // 이메일에 넣을 링크를 만들떄 처음이나 나중에 같은 원문으로 시도하기위해 암호화하여 저장해놨다가 비밀키로 복호화함
        // 검증용 해시와, 작업자가 복호화해서 사용할 발송용 암호문을 함께 저장한다.
        Invitation invitation = new Invitation(
                workspace,
                inviter,
                targetEmail,
                tokenHash,
                encryptedToken,
                now.plusHours(EXPIRATION_HOURS)
        );

        return invitationRepository.save(invitation);
    }

    private void validateInvitation(
            Long workspaceId,
            Long inviterId,
            String targetEmail,
            LocalDateTime now
    ) {
        boolean isMember = workspaceMemberRepository
                .existsByWorkspaceIdAndUserId(workspaceId, inviterId);

        if (!isMember) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "워크스페이스 멤버만 초대할 수 있습니다."
            );
        }

        if (workspaceMemberRepository.existsByWorkspaceIdAndUserEmail(
                workspaceId, targetEmail
        )) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 워크스페이스에 소속된 회원입니다."
            );
        }

        boolean alreadyInvited = invitationRepository
                .existsByWorkspaceIdAndTargetEmailAndInvitationStatusAndExpiresAtAfter(
                        workspaceId,
                        targetEmail,
                        InvitationStatus.PENDING,
                        now
                );

        if (alreadyInvited) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "아직 유효한 초대가 존재합니다."
            );
        }
    }
}
