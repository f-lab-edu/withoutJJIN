package withoutjjin.withoutjjin.domain.workspace;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersionReview;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMemberBan;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberBanRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class WorkspaceMemberApiTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired WorkspaceMemberRepository members;
    @Autowired WorkspaceMemberBanRepository bans;
    @Autowired InvitationTokenGenerator tokens;
    @Autowired InvitationTokenCipher cipher;
    AppUser creator;
    AppUser target;
    AppUser peer;
    AppUser outsider;
    Workspace workspace;
    Workspace anotherWorkspace;
    Document document;
    DocumentVersion version;
    StoreFile file;
    DocumentFeedback feedback;
    DocumentVersionReview review;

    @BeforeEach
    void setUp() {
        creator = user("생성자"); target = user("강퇴 대상"); peer = user("다른 멤버"); outsider = user("외부 회원");
        workspace = new Workspace("멤버 관리 팀", creator); em.persist(workspace);
        anotherWorkspace = new Workspace("다른 팀", outsider); em.persist(anotherWorkspace);
        for (AppUser user : new AppUser[]{creator, target, peer}) em.persist(new WorkspaceMember(workspace, user));
        em.persist(new WorkspaceMember(anotherWorkspace, outsider));
        em.persist(new WorkspaceMember(anotherWorkspace, target));
        String key = "member-api/" + UUID.randomUUID();
        document = new Document("대상 회원의 문서", 1, target, null, workspace); em.persist(document);
        file = new StoreFile(workspace, key, "이력서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L); em.persist(file);
        version = new DocumentVersion(1, "", 2, document, key, "이력서.docx", 1024L, target); em.persist(version);
        feedback = new DocumentFeedback(version, target, 1, .1, .1, .2, .2, FeedbackColor.BLUE, "작성한 의견"); em.persist(feedback);
        review = new DocumentVersionReview(version, target); em.persist(review);
        em.flush(); em.clear();
    }

    @Test
    void creatorKicksMemberAndPreservesAllAuthoredData() throws Exception {
        mvc.perform(delete(memberPath(target)).with(login(creator)).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        em.clear();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        WorkspaceMemberBan ban = bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId()).orElseThrow();
        assertThat(ban.getBannedBy().getId()).isEqualTo(creator.getId()); assertThat(ban.getCreatedAt()).isNotNull();
        assertThat(members.existsByWorkspaceIdAndUserId(anotherWorkspace.getId(), target.getId())).isTrue();
        assertPreserved(); assertProtectedRequestsDenied();
        mvc.perform(get("/api/v1/auth/me").with(login(target))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/workspaces").with(login(target))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(workspace.getId().intValue()))));
        mvc.perform(get(base() + "/member-bans").with(login(creator))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].userId").value(target.getId()))
                .andExpect(jsonPath("$[0].displayName").value(target.getDisplayName()))
                .andExpect(jsonPath("$[0].bannedBy").value(creator.getId()))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].subject").doesNotExist());
        mvc.perform(get("/api/v1/document-versions/" + version.getId() + "/feedback").with(login(peer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("작성한 의견"));
    }

    @Test
    void memberLeavesSelfWithoutBanAndCanRejoinWithValidLink() throws Exception {
        String token = invitation();
        mvc.perform(delete(base() + "/members/me").with(login(target)).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        em.clear();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        assertPreserved(); assertProtectedRequestsDenied();
        accept(token, 200);
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
    }

    @Test
    void allSharedLinksRemainBlockedUntilCreatorUnbansAndRejoiningIsExplicit() throws Exception {
        String first = invitation(), second = invitation();
        mvc.perform(delete(memberPath(target)).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        accept(first, 403); accept(second, 403);
        mvc.perform(delete(banPath(target)).with(login(creator)).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        em.clear();
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        accept(second, 200);
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"peer", "outsider"})
    void nonCreatorCannotKickReadBansOrUnban(String role) throws Exception {
        AppUser actor = role.equals("peer") ? peer : outsider;
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, outsider, creator));
        mvc.perform(delete(memberPath(target)).with(login(actor)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/member-bans").with(login(actor))).andExpect(status().isForbidden());
        mvc.perform(delete(banPath(outsider)).with(login(actor)).with(csrf())).andExpect(status().isForbidden());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), outsider.getId())).isTrue();
    }

    @Test
    void creatorCannotLeaveOrKickSelf() throws Exception {
        mvc.perform(delete(base() + "/members/me").with(login(creator)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(memberPath(creator)).with(login(creator)).with(csrf())).andExpect(status().isForbidden());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), creator.getId())).isTrue();
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), creator.getId())).isFalse();
    }

    @Test
    void leaveUsesPrincipalAndIgnoresSpoofedTargetInRequestBody() throws Exception {
        mvc.perform(delete(base() + "/members/me").with(login(target)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + peer.getId() + "}"))
                .andExpect(status().isNoContent());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), peer.getId())).isTrue();
    }

    @Test
    void outsiderCannotLeaveAndBannedUserCannotClearBanThroughLeave() throws Exception {
        mvc.perform(delete(base() + "/members/me").with(login(outsider)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(memberPath(target)).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(base() + "/members/me").with(login(target)).with(csrf())).andExpect(status().isForbidden());
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
    }

    @Test
    void repeatKickPreservesOriginalBanAndRepeatUnbanDoesNotCreateMembership() throws Exception {
        mvc.perform(delete(memberPath(target)).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        em.flush(); em.clear(); // MySQL에 저장된 마이크로초 정밀도의 시각을 기준으로 비교한다.
        WorkspaceMemberBan first = bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId()).orElseThrow();
        Long id = first.getId(); LocalDateTime createdAt = first.getCreatedAt();
        mvc.perform(delete(memberPath(target)).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        em.clear();
        WorkspaceMemberBan repeated = bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId()).orElseThrow();
        assertThat(repeated.getId()).isEqualTo(id); assertThat(repeated.getCreatedAt()).isEqualTo(createdAt);
        for (int i = 0; i < 2; i++) mvc.perform(delete(banPath(target)).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
    }

    @Test
    void kickRejectsMissingTargetWithoutCreatingBan() throws Exception {
        mvc.perform(delete(memberPath(outsider)).with(login(creator)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete(base() + "/members/" + Long.MAX_VALUE).with(login(creator)).with(csrf())).andExpect(status().isNotFound());
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), outsider.getId())).isFalse();
    }

    @Test
    void allOperationsReturn404ForUnknownWorkspace() throws Exception {
        String missing = "/api/v1/workspaces/" + Long.MAX_VALUE;
        for (String suffix : new String[]{"/members/" + target.getId(), "/members/me", "/member-bans/" + target.getId()}) {
            mvc.perform(delete(missing + suffix).with(login(creator)).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get(missing + "/member-bans").with(login(creator))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositivePathIds(long invalid) throws Exception {
        mvc.perform(delete(base() + "/members/" + invalid).with(login(creator)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete(base() + "/member-bans/" + invalid).with(login(creator)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/workspaces/" + invalid + "/members/me").with(login(target)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workspaces/" + invalid + "/member-bans").with(login(creator))).andExpect(status().isBadRequest());
    }

    @Test
    void authenticationAndCsrfAreRequired() throws Exception {
        for (String path : new String[]{memberPath(target), base() + "/members/me", banPath(target)}) {
            mvc.perform(delete(path).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(delete(path).with(login(creator))).andExpect(status().isForbidden());
        }
        mvc.perform(get(base() + "/member-bans")).andExpect(status().isUnauthorized());
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
    }

    @Test
    void bansAreScopedToWorkspaceAndDoNotRevealOtherWorkspaceRows() throws Exception {
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        bans.saveAndFlush(new WorkspaceMemberBan(anotherWorkspace, peer, outsider));
        mvc.perform(get(base() + "/member-bans").with(login(creator))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].userId").value(target.getId()));
        mvc.perform(delete(base() + "/member-bans/" + peer.getId()).with(login(creator)).with(csrf())).andExpect(status().isNoContent());
        assertThat(bans.existsByWorkspaceIdAndUserId(anotherWorkspace.getId(), peer.getId())).isTrue();
        mvc.perform(get("/api/v1/workspaces/" + anotherWorkspace.getId() + "/member-bans").with(login(creator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void banIsCheckedEvenIfInconsistentExistingMembershipIsPresent() throws Exception {
        String token = invitation();
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        accept(token, 403);
    }

    private void assertPreserved() {
        assertThat(em.find(AppUser.class, target.getId())).isNotNull();
        assertThat(em.find(Document.class, document.getId()).getCreatedBy().getId()).isEqualTo(target.getId());
        assertThat(em.find(DocumentVersion.class, version.getId()).getUploadedBy().getId()).isEqualTo(target.getId());
        assertThat(em.find(StoreFile.class, file.getId()).getObjectKey()).isEqualTo(file.getObjectKey());
        assertThat(em.find(DocumentFeedback.class, feedback.getId()).getContent()).isEqualTo("작성한 의견");
        assertThat(em.find(DocumentVersionReview.class, review.getId()).getReviewer().getId()).isEqualTo(target.getId());
    }

    private void assertProtectedRequestsDenied() throws Exception {
        mvc.perform(get(base()).with(login(target))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/documents").with(login(target))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/document-versions/" + version.getId() + "/feedback").with(login(target)))
                .andExpect(status().isForbidden());
    }

    private String invitation() {
        String token = tokens.generateToken();
        em.persist(new Invitation(em.getReference(Workspace.class, workspace.getId()), em.getReference(AppUser.class, creator.getId()),
                tokens.hashToken(token), cipher.encrypt(token), LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
        em.flush(); return token;
    }
    private void accept(String token, int expected) throws Exception {
        mvc.perform(post("/api/v1/invitations/accept").with(login(target)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().is(expected));
    }
    private String base() { return "/api/v1/workspaces/" + workspace.getId(); }
    private String memberPath(AppUser user) { return base() + "/members/" + user.getId(); }
    private String banPath(AppUser user) { return base() + "/member-bans/" + user.getId(); }
    private AppUser user(String name) {
        String subject = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://accounts.google.com", subject, subject + "@example.invalid", true, name);
        em.persist(user); return user;
    }
    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }
}
