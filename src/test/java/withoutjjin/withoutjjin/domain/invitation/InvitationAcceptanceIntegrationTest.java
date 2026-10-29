package withoutjjin.withoutjjin.domain.invitation;

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
    void 로그인_전에도_유효한_초대의_최소_정보를_확인한다() throws Exception {
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
    void 없는_토큰의_확인과_수락은_404를_반환한다() throws Exception {
        String unknown = tokens.generateToken();
        mockMvc.perform(get("/api/v1/invitations").param("token", unknown))
                .andExpect(status().isNotFound());
        accept(unknown, loginAs(invitee, true)).andExpect(status().isNotFound());
    }

    @Test
    void 확인_요청에_토큰이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/invitations"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 빈_토큰의_확인은_400을_반환한다(String value) throws Exception {
        mockMvc.perform(get("/api/v1/invitations").param("token", value))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 너무_긴_토큰은_확인과_수락에서_거부한다() throws Exception {
        String tooLong = "a".repeat(257);
        mockMvc.perform(get("/api/v1/invitations").param("token", tooLong))
                .andExpect(status().isBadRequest());
        accept(tooLong, loginAs(invitee, true)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void 만료시각이_현재와_같거나_지났으면_확인과_수락을_거부한다(int seconds) throws Exception {
        invitation = saveInvitation(NOW.plusSeconds(seconds));
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isGone());
        accept(token, loginAs(invitee, true)).andExpect(status().isGone());
        assertNotJoined();
    }

    @Test
    void 취소된_초대는_확인과_수락을_거부한다() throws Exception {
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
    void 인증된_초대_이메일의_회원이_수락하면_멤버와_초대_상태를_저장한다() throws Exception {
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
    void 로그인_이메일과_초대_이메일은_대소문자를_구분하지_않고_비교한다() throws Exception {
        OidcLoginRequestPostProcessor login = oidcLogin().idToken(t -> t
                .claim("iss", invitee.getIssuer()).subject(invitee.getSubject())
                .claim("email", invitee.getEmail().toUpperCase(java.util.Locale.ROOT))
                .claim("email_verified", true));
        accept(token, login).andExpect(status().isOk());
    }

    @Test
    void 다른_이메일_계정은_수락할_수_없다() throws Exception {
        accept(token, loginAs(creator, true)).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    void DB에서_이메일이_인증됐어도_현재_로그인_결과가_미인증이면_거부한다() throws Exception {
        accept(token, loginAs(invitee, false)).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    void 현재_로그인_결과에_이메일_인증_정보가_없어도_거부한다() throws Exception {
        OidcLoginRequestPostProcessor login = oidcLogin().idToken(t -> t
                .claim("iss", invitee.getIssuer()).subject(invitee.getSubject())
                .claim("email", invitee.getEmail()));
        accept(token, login).andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    void 요청본문에_초대이메일과_인증여부를_넣어도_로그인계정을_대신할_수_없다() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(loginAs(creator, true))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"email\":\"" + invitee.getEmail()
                                + "\",\"emailVerified\":true}"))
                .andExpect(status().isForbidden());
        assertNotJoined();
    }

    @Test
    void 같은_수락_요청은_만료_후_재전송해도_중복가입_없이_성공한다() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        long count = members.count();
        when(clock.instant()).thenReturn(NOW.plusDays(2).atZone(ZONE).toInstant());
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        assertThat(members.count()).isEqualTo(count);
    }

    @Test
    void 이미_멤버인_상태에서_유효한_초대를_수락해도_중복가입하지_않는다() throws Exception {
        members.saveAndFlush(new WorkspaceMember(workspace, invitee));
        long count = members.count();
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        assertThat(members.count()).isEqualTo(count);
    }

    @Test
    void 이미_수락한_링크는_미리보기에서_409를_반환한다() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/invitations").param("token", token))
                .andExpect(status().isConflict());
    }

    @Test
    void 탈퇴한_회원은_사용했던_초대링크로_다시_가입하지_못한다() throws Exception {
        accept(token, loginAs(invitee, true)).andExpect(status().isOk());
        entityManager.createQuery("delete from WorkspaceMember m where m.workspace.id = :workspaceId and m.user.id = :userId")
                .setParameter("workspaceId", workspace.getId()).setParameter("userId", invitee.getId())
                .executeUpdate();
        entityManager.clear();
        accept(token, loginAs(invitee, true)).andExpect(status().isConflict());
        assertNotJoined();
    }

    @Test
    void 비로그인_수락은_401을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CSRF토큰_없이_수락하면_403을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/accept").with(loginAs(invitee, true))
                        .contentType(MediaType.APPLICATION_JSON).content(body(token)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 빈_수락_토큰은_400을_반환한다(String value) throws Exception {
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
