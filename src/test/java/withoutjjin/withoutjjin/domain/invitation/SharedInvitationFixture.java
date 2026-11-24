package withoutjjin.withoutjjin.domain.invitation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationAcceptanceService;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationService;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

abstract class SharedInvitationFixture {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);
    @Autowired InvitationRepository invitations;
    @Autowired InvitationTokenGenerator tokens;
    @Autowired InvitationTokenCipher cipher;
    @Autowired InvitationService service;
    @Autowired InvitationAcceptanceService acceptance;
    @Autowired AppUserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @MockitoBean(name = "invitationClock") Clock clock;
    final List<Long> createdUserIds = new ArrayList<>();
    AppUser creator;
    AppUser member;
    AppUser outsider;
    Workspace workspace;

    void setUpFixture() {
        setTime(NOW);
        creator = saveUser();
        member = saveUser();
        outsider = saveUser();
        workspace = workspaces.saveAndFlush(new Workspace("공유 초대 팀", creator));
        members.saveAndFlush(new WorkspaceMember(workspace, creator));
        members.saveAndFlush(new WorkspaceMember(workspace, member));
    }

    void setTime(LocalDateTime now) {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenReturn(now.atZone(ZONE).toInstant());
    }

    AppUser saveUser() {
        String unique = UUID.randomUUID().toString();
        AppUser user = users.saveAndFlush(new AppUser("https://accounts.google.com", unique,
                unique + "@example.com", true, "공유 초대 회원"));
        createdUserIds.add(user.getId());
        return user;
    }

    String base() { return "/api/v1/workspaces/" + workspace.getId() + "/invitations"; }

    Invitation saveInvitation(String token) {
        return invitations.saveAndFlush(new Invitation(workspace, creator, tokens.hashToken(token), cipher.encrypt(token), NOW));
    }

    OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject())
                .claim("email", user.getEmail()).claim("email_verified", true));
    }
}
