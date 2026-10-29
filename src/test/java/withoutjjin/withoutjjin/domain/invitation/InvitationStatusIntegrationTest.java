package withoutjjin.withoutjjin.domain.invitation;

import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("멤버는 초대 상태를 조회하고 토큰은 응답에 포함하지 않는다")
    void returnsInvitationStatusForMemberWithoutExposingToken() throws Exception {
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
    @DisplayName("재시도 대기 상태와 다음 실행시각을 조회한다")
    void returnsRetryPendingStateAndNextRetryTime() throws Exception {
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
    @DisplayName("SES접수 상태와 초대수락 상태는 구분한다")
    void distinguishesMailSubmissionFromInvitationAcceptance() throws Exception {
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
    @DisplayName("비로그인 요청은 401을 반환한다")
    void returns401ForAnonymousStatusRequest() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 팀 회원은 이메일을 조회할 수 없다")
    void preventsOtherWorkspaceMembersFromViewingInviteeEmail() throws Exception {
        mockMvc.perform(get(path()).with(loginAs(saveUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.targetEmail").doesNotExist());
    }

    @Test
    @DisplayName("선택한 팀에 없는 초대는 조회할 수 없다")
    void rejectsInvitationOutsideSelectedWorkspace() throws Exception {
        Workspace another = workspaces.save(new Workspace("다른 팀", member));
        members.saveAndFlush(new WorkspaceMember(another, member));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/invitations/{id}",
                        another.getId(), invitation.getId()).with(loginAs(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 초대는 404를 반환한다")
    void returns404ForMissingInvitation() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/invitations/{id}",
                        workspace.getId(), Long.MAX_VALUE).with(loginAs(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("테스트화면은 공개하지만 API는 로그인이 필요하다")
    void servesPublicTestPageButRequiresLoginForApi() throws Exception {
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
