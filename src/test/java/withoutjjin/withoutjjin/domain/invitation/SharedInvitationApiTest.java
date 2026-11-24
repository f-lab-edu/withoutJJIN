package withoutjjin.withoutjjin.domain.invitation;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class SharedInvitationApiTest extends SharedInvitationFixture {
    @Autowired MockMvc mvc;

    @BeforeEach void setUp() { setUpFixture(); }

    @Test
    void creatorCanRetrieveSameTokenWithoutExtendingExpiry() throws Exception {
        String body = mvc.perform(post(base()).with(login(creator)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.invitationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.targetEmail").doesNotExist())
                .andExpect(jsonPath("$.encryptedToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        Invitation stored = invitations.findById(id).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(tokens.hashToken(token));
        assertThat(stored.getEncryptedToken()).doesNotContain(token);
        assertThat(cipher.decrypt(stored.getEncryptedToken())).isEqualTo(token);
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusDays(7));
        setTime(NOW.plusDays(6));
        mvc.perform(get(base() + "/" + id).with(login(creator)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.expiresAt").value("2026-12-02T12:00:00"));
        mvc.perform(get(base()).with(login(creator))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$[0].encryptedToken").doesNotExist());
    }

    @Test
    void multipleUsersCanJoinAndRepeatWithoutConsumingInvitation() throws Exception {
        String token = tokens.generateToken();
        Invitation invitation = saveInvitation(token);
        String encrypted = invitation.getEncryptedToken();
        mvc.perform(get("/api/v1/invitations").param("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.invitedBy").doesNotExist());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), outsider.getId())).isFalse();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/invitations/accept").with(login(outsider)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.invitationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
        var another = saveUser();
        acceptance.acceptInvitation(token, another.getIssuer(), another.getSubject());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), outsider.getId())).isTrue();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), another.getId())).isTrue();
        assertThat(invitation.getInvitationStatus()).isEqualTo(InvitationStatus.ACTIVE);
        assertThat(invitation.getEncryptedToken()).isEqualTo(encrypted);
        assertThat(invitation.getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "outsider"})
    void nonCreatorCannotManageOrReadToken(String who) throws Exception {
        Invitation invitation = saveInvitation(tokens.generateToken());
        var user = who.equals("member") ? member : outsider;
        mvc.perform(post(base()).with(login(user)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(base()).with(login(user))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/" + invitation.getId()).with(login(user))).andExpect(status().isForbidden());
        mvc.perform(delete(base() + "/" + invitation.getId()).with(login(user)).with(csrf())).andExpect(status().isForbidden());
        assertThat(invitation.getInvitationStatus()).isEqualTo(InvitationStatus.ACTIVE);
    }

    @Test
    void revokeIsIdempotentAndImmediatelyRejectsPreviewAcceptanceAndTokenRead() throws Exception {
        String token = tokens.generateToken();
        Invitation invitation = saveInvitation(token);
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete(base() + "/" + invitation.getId()).with(login(creator)).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/v1/invitations").param("token", token)).andExpect(status().isGone());
        mvc.perform(post("/api/v1/invitations/accept").with(login(outsider)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone());
        mvc.perform(get(base() + "/" + invitation.getId()).with(login(creator))).andExpect(status().isGone());
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    void exactSevenDayBoundaryRejectsEvenAlreadyJoinedMember() throws Exception {
        String token = tokens.generateToken();
        Invitation invitation = saveInvitation(token);
        acceptance.acceptInvitation(token, outsider.getIssuer(), outsider.getSubject());
        setTime(NOW.plusDays(7));
        mvc.perform(get("/api/v1/invitations").param("token", token)).andExpect(status().isGone());
        mvc.perform(post("/api/v1/invitations/accept").with(login(outsider)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone());
        mvc.perform(get(base() + "/" + invitation.getId()).with(login(creator))).andExpect(status().isGone());
    }

    @Test
    void requiresAuthenticationAndCsrfForMutations() throws Exception {
        Invitation invitation = saveInvitation(tokens.generateToken());
        mvc.perform(post(base()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(base()).with(login(creator))).andExpect(status().isForbidden());
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        mvc.perform(delete(base() + "/" + invitation.getId()).with(login(creator))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/invitations/accept").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"token\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/invitations/accept").with(login(outsider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"token\"}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "oversized"})
    void rejectsInvalidTokenInput(String value) throws Exception {
        String token = value.equals("oversized") ? "x".repeat(257) : value;
        mvc.perform(get("/api/v1/invitations").param("token", token)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/invitations/accept").with(login(outsider)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTokenAndWrongWorkspaceDoNotResolveInvitation() throws Exception {
        mvc.perform(get("/api/v1/invitations").param("token", tokens.generateToken())).andExpect(status().isNotFound());
        Invitation invitation = saveInvitation(tokens.generateToken());
        Workspace another = workspaces.saveAndFlush(new Workspace("다른 팀", creator));
        members.saveAndFlush(new WorkspaceMember(another, creator));
        mvc.perform(get("/api/v1/workspaces/" + another.getId() + "/invitations/" + invitation.getId()).with(login(creator)))
                .andExpect(status().isNotFound());
    }

    @Test
    void mismatchedCiphertextIsNeverReturnedToCreator() {
        String original = tokens.generateToken();
        Invitation invitation = invitations.saveAndFlush(new Invitation(workspace, creator, tokens.hashToken(original),
                cipher.encrypt(tokens.generateToken()), NOW));
        assertThatIllegalStateException().isThrownBy(() -> service.getInvitation(workspace.getId(), invitation.getId(),
                creator.getIssuer(), creator.getSubject())).withMessage("초대 토큰 검증에 실패했습니다.");
    }
}
