package withoutjjin.withoutjjin.domain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.MockMvc;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 테스트 전체의 영속성 컨텍스트에 기대지 않고, Service에서 준비한 응답으로 조회되는지 검사한다.
@SpringBootTest(properties = {"spring.jpa.open-in-view=false", "invitation.mail.enabled=false"})
@AutoConfigureMockMvc
class WorkspaceDetailIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RequestCache requestCache;
    @Autowired private SavedRequestAwareAuthenticationSuccessHandler successHandler;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> workspaceIds = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 만든사람과_초대받아_가입한_멤버_모두_팀과_멤버를_조회한다(boolean asCreator) throws Exception {
        AppUser creator = user("만든 사람");
        AppUser invitee = user("참여한 사람");
        Workspace workspace = workspace(creator);
        members.saveAndFlush(new WorkspaceMember(workspace, invitee));
        mockMvc.perform(get("/api/v1/workspaces/" + workspace.getId())
                        .with(login(asCreator ? creator : invitee)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(workspace.getId()))
                .andExpect(jsonPath("$.name").value(workspace.getName()))
                .andExpect(jsonPath("$.createdBy").value(creator.getId()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].userId").value(creator.getId()))
                .andExpect(jsonPath("$.members[0].displayName").value("만든 사람"))
                .andExpect(jsonPath("$.members[0].joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.members[1].userId").value(invitee.getId()))
                .andExpect(jsonPath("$.members[1].displayName").value("참여한 사람"))
                .andExpect(jsonPath("$.members[0].email").doesNotExist())
                .andExpect(jsonPath("$.members[0].issuer").doesNotExist())
                .andExpect(jsonPath("$.members[0].subject").doesNotExist());
    }

    @Test
    void 소속이_아닌_계정은_팀정보와_멤버를_볼_수_없다() throws Exception {
        Workspace workspace = workspace(user("만든 사람"));
        AppUser outsider = user("다른 회원");
        mockMvc.perform(get("/api/v1/workspaces/" + workspace.getId()).with(login(outsider)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(content().string(not(containsString(workspace.getName()))));
    }

    @Test
    void 탈퇴한_멤버는_같은_주소로_다시_조회할_수_없다() throws Exception {
        AppUser creator = user("만든 사람");
        Workspace workspace = workspace(creator);
        mockMvc.perform(get("/api/v1/workspaces/" + workspace.getId()).with(login(creator)))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), creator.getId());
        mockMvc.perform(get("/api/v1/workspaces/" + workspace.getId()).with(login(creator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_팀은_404다() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces/" + Long.MAX_VALUE).with(login(user("회원"))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void 잘못된_팀번호는_400이다(String value) throws Exception {
        mockMvc.perform(get("/api/v1/workspaces/" + value).with(oidcLogin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 비로그인_API는_401이고_팀화면은_Google_로그인으로_연결한다() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces/123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/workspaces/123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", endsWith("/oauth2/authorization/google")));
    }

    @Test
    void 로그인한_회원에게_팀화면을_제공하고_실제정보는_API에서_조회한다() throws Exception {
        mockMvc.perform(get("/workspaces/123").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("함께하는 멤버")));
    }

    @Test
    void 팀화면에서_시작한_로그인은_같은_팀주소로_돌아간다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspaces/123");
        request.setServletPath("/workspaces/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        requestCache.saveRequest(request, response);
        successHandler.onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).startsWith("http://localhost/workspaces/123");
    }

    private AppUser user(String name) {
        AppUser user = users.saveAndFlush(new AppUser("https://accounts.google.com",
                UUID.randomUUID().toString(), "workspace-detail-test@example.com", true, name));
        userIds.add(user.getId());
        return user;
    }

    private Workspace workspace(AppUser creator) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace("팀 공간 조회 테스트", creator));
        workspaceIds.add(workspace.getId());
        members.saveAndFlush(new WorkspaceMember(workspace, creator));
        return workspace;
    }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer())
                .subject(user.getSubject()));
    }

    @AfterEach
    void 이번_테스트에서_생성한_데이터만_정리한다() {
        for (Long id : workspaceIds) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", id);
            workspaces.deleteById(id);
        }
        for (Long id : userIds) users.deleteById(id);
    }
}
