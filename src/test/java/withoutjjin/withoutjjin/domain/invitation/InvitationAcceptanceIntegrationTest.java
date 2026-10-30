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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "invitation.mail.enabled=false")
@AutoConfigureMockMvc
@Transactional
class InvitationAcceptanceIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private InvitationRepository invitations;
    @Autowired private InvitationTokenGenerator tokens;
    @Autowired private InvitationTokenCipher cipher;
    @Autowired private EntityManager entityManager;
    @MockitoBean(name = "invitationMailClock") private Clock clock;

    private AppUser creator;
    private AppUser invitee;
    private Workspace workspace;
    private Invitation invitation;
    private String token;

    @BeforeEach
    void setUp() {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenReturn(NOW.atZone(ZONE).toInstant());
        creator = saveUser();
        invitee = saveUser();
        workspace = workspaces.save(new Workspace("링크 확인 테스트 팀", creator));
        members.save(new WorkspaceMember(workspace, creator));
        invitation = saveInvitation(NOW.plusHours(24));
    }

    @Test
    @DisplayName("로그인 전에도 유효한 초대의 최소 정보를 확인한다")
    void previewsMinimalInvitationDetailsBeforeLogin() throws Exception {
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
                .andExpect(jsonPath("$.workspaceName").value(workspace.getName()))
                .andExpect(jsonPath("$.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.targetEmail").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andExpect(jsonPath("$.encryptedToken").doesNotExist());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), invitee.getId())).isFalse();
    }

    @Test
    @DisplayName("없는 토큰의 확인과 수락은 404를 반환한다")
    void returns404ForPreviewAndAcceptanceWithUnknownToken() throws Exception {
        String unknown = tokens.generateToken();
        mockMvc.perform(get("/api/v1/invitations").param("token", unknown))
                .andExpect(status().isNotFound());
        accept(unknown, loginAs(invitee, true)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("확인 요청에 토큰이 없으면 400을 반환한다")
    void returns400ForPreviewWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/invitations"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("빈 토큰의 확인은 400을 반환한다")
    void returns400ForPreviewWithBlankToken(String value) throws Exception {
        mockMvc.perform(get("/api/v1/invitations").param("token", value))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("너무 긴 토큰은 확인과 수락에서 거부한다")
    void rejectsOversizedTokenForPreviewAndAcceptance() throws Exception {
        String tooLong = "a".repeat(257);
        mockMvc.perform(get("/api/v1/invitations").param("token", tooLong))
                .andExpect(status().isBadRequest());
        accept(tooLong, loginAs(invitee, true)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    @DisplayName("만료시각이 현재와 같거나 지났으면 확인과 수락을 거부한다")
    void rejectsPreviewAndAcceptanceAtOrAfterExpiry(int seconds) throws Exception {
        invitation = saveInvitation(NOW.plusSeconds(seconds));
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isGone());
        accept(token, loginAs(invitee, true)).andExpect(status().isGone());
        assertNotJoined();
    }

    @Test
    @DisplayName("취소된 초대는 확인과 수락을 거부한다")
    void rejectsPreviewAndAcceptanceOfCanceledInvitation() throws Exception {
        entityManager.createQuery("update Invitation i set i.invitationStatus = :status where i.id = :id")
                .setParameter("status", InvitationStatus.CANCELED).setParameter("id", invitation.getId())
                .executeUpdate();
        entityManager.clear();
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isGone());
        accept(token, loginAs(invitee, true)).andExpect(status().isGone());
        assertNotJoined();
    }

    @Test
    @DisplayName("인증된 초대 이메일의 회원이 수락하면 멤버와 초대 상태를 저장한다")
    void savesMembershipAndAcceptedStateForVerifiedInvitee() throws Exception {
        accept(token, loginAs(invitee, true))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.invitationId").value(invitation.getId()))
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
                .andExpect(jsonPath("$.invitationStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.token").doesNotExist());
        invitations.flush();
        entityManager.clear();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), invitee.getId())).isTrue();
        Invitation stored = invitations.findById(invitation.getId()).orElseThrow();
        assertThat(stored.getInvitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(stored.getEncryptedToken()).isNull();
        assertThat(stored.getNextRetryAt()).isNull();
        assertThat(stored.getTokenHash()).isEqualTo(tokens.hashToken(token));
    }

    @Test
    @DisplayName("로그인 이메일과 초대 이메일은 대소문자를 구분하지 않고 비교한다")
    void comparesLoginAndInvitationEmailsIgnoringCase() throws Exception {
        OidcLoginRequestPostProcessor login = oidcLogin().idToken(t -> t
                .claim("iss", invitee.getIssuer()).subject(invitee.getSubject())
                .claim("email", invitee.getEmail().toUpperCase(java.util.Locale.ROOT))
                .claim("email_verified", true));
        accept(token, login).andExpect(status().isOk());
    }

    @Test
    @DisplayName("다른 이메일 계정은 수락할 수 없다")
    void rejectsAcceptanceWithDifferentEmail() throws Exception {
        accept(token, loginAs(creator, true)).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    @DisplayName("DB에서 이메일이 인증됐어도 현재 로그인 결과가 미인증이면 거부한다")
    void rejectsUnverifiedLoginEvenWhenDatabaseEmailIsVerified() throws Exception {
        accept(token, loginAs(invitee, false)).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    @DisplayName("현재 로그인 결과에 이메일 인증 정보가 없어도 거부한다")
    void rejectsLoginWithoutEmailVerificationClaim() throws Exception {
        OidcLoginRequestPostProcessor login = oidcLogin().idToken(t -> t
                .claim("iss", invitee.getIssuer()).subject(invitee.getSubject())
                .claim("email", invitee.getEmail()));
        accept(token, login).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    @DisplayName("요청본문에 초대이메일과 인증여부를 넣어도 로그인계정을 대신할 수 없다")
    void doesNotTrustEmailAndVerificationFieldsInRequestBody() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(loginAs(creator, true))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"email\":\"" + invitee.getEmail()
                                + "\",\"emailVerified\":true}"))
                .andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    @DisplayName("같은 수락 요청은 만료 후 재전송해도 중복가입 없이 성공한다")
    void acceptsRepeatedRequestAfterExpiryWithoutDuplicateMembership() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        long count = members.count();
        when(clock.instant()).thenReturn(NOW.plusDays(2).atZone(ZONE).toInstant());
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        assertThat(members.count()).isEqualTo(count);
    }

    @Test
    @DisplayName("이미 멤버인 상태에서 유효한 초대를 수락해도 중복가입하지 않는다")
    void acceptsValidInvitationWithoutDuplicatingExistingMembership() throws Exception {
        members.saveAndFlush(new WorkspaceMember(workspace, invitee));
        long count = members.count();
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        assertThat(members.count()).isEqualTo(count);
    }

    @Test
    @DisplayName("이미 수락한 링크는 미리보기에서 409를 반환한다")
    void returns409ForPreviewOfAcceptedInvitation() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("탈퇴한 회원은 사용했던 초대링크로 다시 가입하지 못한다")
    void preventsFormerMemberFromRejoiningWithUsedInvitation() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        entityManager.createQuery("delete from WorkspaceMember m where m.workspace.id = :workspaceId and m.user.id = :userId")
                .setParameter("workspaceId", workspace.getId()).setParameter("userId", invitee.getId())
                .executeUpdate();
        entityManager.clear();
        accept(token, loginAs(invitee, true)).andExpect(status().isConflict());
        assertNotJoined();
    }

    @Test
    @DisplayName("비로그인 수락은 401을 반환한다")
    void returns401ForAnonymousAcceptance() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF토큰 없이 수락하면 403을 반환한다")
    void returns403ForAcceptanceWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(loginAs(invitee, true))
                        .contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("빈 수락 토큰은 400을 반환한다")
    void returns400ForAcceptanceWithBlankToken(String value) throws Exception {
        accept(value, loginAs(invitee, true)).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            String token, OidcLoginRequestPostProcessor login
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/invitations/accept").with(login).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content(body(token)));
    }

    private String body(String token) {
        return token == null ? "{}" : "{\"token\":\"" + token + "\"}";
    }

    private void assertNotJoined() {
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), invitee.getId())).isFalse();
    }

    private AppUser saveUser() {
        String id = UUID.randomUUID().toString();
        return users.save(new AppUser("https://accounts.google.com", id,
                id + "@example.com", true, "링크 테스트 회원"));
    }

    private Invitation saveInvitation(LocalDateTime expiresAt) {
        token = tokens.generateToken();
        return invitations.saveAndFlush(new Invitation(workspace, creator, invitee.getEmail(),
                tokens.hashToken(token), cipher.encrypt(token), expiresAt));
    }

    private OidcLoginRequestPostProcessor loginAs(AppUser user, boolean verified) {
        return oidcLogin().idToken(t -> t.claim("iss", user.getIssuer()).subject(user.getSubject())
                .claim("email", user.getEmail()).claim("email_verified", verified));
    }
}
