package withoutjjin.withoutjjin.domain.invitation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "invitation.mail.enabled=false")
@AutoConfigureMockMvc
@Transactional
class InvitationStatusIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private InvitationRepository invitations;
    @Autowired private InvitationTokenGenerator generator;
    @Autowired private InvitationTokenCipher cipher;
    @Autowired private EntityManager entityManager;

    private AppUser member;
    private Workspace workspace;
    private Invitation invitation;

    @BeforeEach
    void setUp() {
        member = saveUser();
        workspace = workspaces.save(new Workspace("상태 조회 테스트 팀", member));
        members.save(new WorkspaceMember(workspace, member));
        String token = generator.generateToken();
        invitation = invitations.saveAndFlush(new Invitation(workspace, member,
                UUID.randomUUID() + "@example.com", generator.hashToken(token), cipher.encrypt(token),
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusHours(24)));
    }

    @Test
    void 멤버는_초대_상태를_조회하고_토큰은_응답에_포함하지_않는다() throws Exception {
        entityManager.clear();
        mockMvc.perform(get(path()).with(loginAs(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(invitation.getId()))
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
                .andExpect(jsonPath("$.targetEmail").value(invitation.getTargetEmail()))
                .andExpect(jsonPath("$.emailSendStatus").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.encryptedToken").doesNotExist());
    }

    @Test
    void 재시도_대기_상태와_다음_실행시각을_조회한다() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        invitation.startProcessing(now);
        invitation.markFailed(now, true);
        invitations.flush();
        entityManager.clear();

        mockMvc.perform(get(path()).with(loginAs(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSendStatus").value("FAILED"))
                .andExpect(jsonPath("$.nextRetryAt").isNotEmpty());
    }

    @Test
    void SES접수_상태와_초대수락_상태는_구분한다() throws Exception {
        invitation.startProcessing(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        invitation.markSubmitted();
        invitations.flush();
        entityManager.clear();

        mockMvc.perform(get(path()).with(loginAs(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSendStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.invitationStatus").value("PENDING"));
    }

    @Test
    void 비로그인_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_팀_회원은_이메일을_조회할_수_없다() throws Exception {
        mockMvc.perform(get(path()).with(loginAs(saveUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.targetEmail").doesNotExist());
    }

    @Test
    void 선택한_팀에_없는_초대는_조회할_수_없다() throws Exception {
        Workspace another = workspaces.save(new Workspace("다른 팀", member));
        members.saveAndFlush(new WorkspaceMember(another, member));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/invitations/{id}",
                        another.getId(), invitation.getId()).with(loginAs(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 존재하지_않는_초대는_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/invitations/{id}",
                        workspace.getId(), Long.MAX_VALUE).with(loginAs(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 테스트화면은_공개하지만_API는_로그인이_필요하다() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8)).contains("팀원 초대"));
        mockMvc.perform(get("/api/v1/workspaces")).andExpect(status().isUnauthorized());
    }

    private String path() {
        return "/api/v1/workspaces/" + workspace.getId() + "/invitations/" + invitation.getId();
    }

    private AppUser saveUser() {
        String id = UUID.randomUUID().toString();
        return users.save(new AppUser("https://accounts.google.com", id,
                id + "@example.com", true, "상태 조회 테스트 회원"));
    }

    private OidcLoginRequestPostProcessor loginAs(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }
}
