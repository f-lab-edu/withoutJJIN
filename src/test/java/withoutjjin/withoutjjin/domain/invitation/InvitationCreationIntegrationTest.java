package withoutjjin.withoutjjin.domain.invitation;

import org.junit.jupiter.api.DisplayName;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InvitationCreationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private InvitationTokenCipher tokenCipher;
    @Autowired
    private InvitationTokenGenerator tokenGenerator;

    private AppUser inviter;
    private Workspace workspace;
    private String targetEmail;

    @BeforeEach
    void setUp() {
        inviter = saveUser();
        workspace = workspaceRepository.save(new Workspace("초대 테스트 팀", inviter));
        workspaceMemberRepository.save(new WorkspaceMember(workspace, inviter));
        targetEmail = UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("멤버가 초대를 생성하면 해시와 초기상태를 저장한다")
    void savesTokenHashAndInitialStateWhenMemberCreatesInvitation() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        mockMvc.perform(inviteRequest(targetEmail).with(loginAs(inviter)).with(csrf().asHeader()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
                .andExpect(jsonPath("$.invitedBy").value(inviter.getId()))
                .andExpect(jsonPath("$.targetEmail").value(targetEmail))
                .andExpect(jsonPath("$.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.emailSendStatus").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.encryptedToken").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());

        invitationRepository.flush();
        entityManager.clear();

        Invitation saved = entityManager.createQuery(
                        "select i from Invitation i where i.targetEmail = :email", Invitation.class)
                .setParameter("email", targetEmail)
                .getSingleResult();

        assertThat(saved.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(saved.getEncryptedToken()).startsWith("v1:");
        String restoredToken = tokenCipher.decrypt(saved.getEncryptedToken());
        assertThat(restoredToken).hasSize(43);
        assertThat(tokenGenerator.hashToken(restoredToken)).isEqualTo(saved.getTokenHash());
        assertThat(saved.getEncryptedToken()).doesNotContain(restoredToken);
        assertThat(saved.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(saved.getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
        assertThat(saved.getRetryCount()).isZero();
        // MySQL이 소수 초를 반올림할 수 있어 양 끝에 1초 여유를 둔다.
        assertThat(saved.getExpiresAt()).isBetween(
                before.plusHours(24).minusSeconds(1),
                LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusHours(24).plusSeconds(1)
        );
    }

    @Test
    @DisplayName("유효한 초대가 있으면 중복 생성을 거부한다")
    void rejectsDuplicateActiveInvitation() throws Exception {
        saveExistingInvitation(LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1));
        long before = invitationRepository.count();

        mockMvc.perform(inviteRequest(targetEmail).with(loginAs(inviter)).with(csrf().asHeader()))
                .andExpect(status().isConflict());

        assertThat(invitationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("기존 초대가 만료되었으면 새 초대를 생성한다")
    void createsNewInvitationWhenPreviousInvitationHasExpired() throws Exception {
        saveExistingInvitation(LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        long before = invitationRepository.count();

        mockMvc.perform(inviteRequest(targetEmail).with(loginAs(inviter)).with(csrf().asHeader()))
                .andExpect(status().isCreated());

        assertThat(invitationRepository.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("이미 소속된 회원에게는 초대할 수 없다")
    void rejectsInvitationForExistingMember() throws Exception {
        long before = invitationRepository.count();

        mockMvc.perform(inviteRequest(inviter.getEmail()).with(loginAs(inviter)).with(csrf().asHeader()))
                .andExpect(status().isConflict());

        assertThat(invitationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("멤버가 아니면 초대할 수 없다")
    void rejectsInvitationFromNonMember() throws Exception {
        AppUser outsider = saveUser();
        long before = invitationRepository.count();

        mockMvc.perform(inviteRequest(targetEmail).with(loginAs(outsider)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());

        assertThat(invitationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("없는 워크스페이스는 404를 반환한다")
    void returns404ForMissingWorkspace() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/invitations", Long.MAX_VALUE)
                        .with(loginAs(inviter)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetEmail\":\"invitee@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "invalid-email"})
    @DisplayName("잘못된 이메일은 저장하지 않는다")
    void rejectsInvalidEmailWithoutSaving(String email) throws Exception {
        long before = invitationRepository.count();

        mockMvc.perform(inviteRequest(email).with(loginAs(inviter)).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());

        assertThat(invitationRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("로그인하지 않으면 401을 반환한다")
    void returns401ForAnonymousInvitationCreation() throws Exception {
        mockMvc.perform(inviteRequest(targetEmail).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("csrf 토큰이 없으면 403을 반환한다")
    void returns403ForInvitationCreationWithoutCsrfToken() throws Exception {
        mockMvc.perform(inviteRequest(targetEmail).with(loginAs(inviter)))
                .andExpect(status().isForbidden());
    }

    private AppUser saveUser() {
        String uniqueId = UUID.randomUUID().toString();
        return appUserRepository.save(new AppUser(
                "https://accounts.google.com", uniqueId,
                uniqueId + "@example.com", true, "초대 테스트 회원"
        ));
    }

    private void saveExistingInvitation(LocalDateTime expiresAt) {
        String token = tokenGenerator.generateToken();
        invitationRepository.saveAndFlush(new Invitation(
                workspace, inviter, targetEmail,
                tokenGenerator.hashToken(token), tokenCipher.encrypt(token), expiresAt
        ));
    }

    private MockHttpServletRequestBuilder inviteRequest(String email) {
        String body = email == null ? "{}" : "{\"targetEmail\":\"" + email + "\"}";
        return post("/api/v1/workspaces/{workspaceId}/invitations", workspace.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private OidcLoginRequestPostProcessor loginAs(AppUser user) {
        return oidcLogin().idToken(token -> token
                .claim("iss", user.getIssuer())
                .subject(user.getSubject()));
    }
}
