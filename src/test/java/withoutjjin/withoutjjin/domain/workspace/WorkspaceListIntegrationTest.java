package withoutjjin.withoutjjin.domain.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Controller가 Service 밖에서 DTO로 변환해도 필요한 팀 정보를 사용할 수 있어야 한다.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WorkspaceListIntegrationTest {

    // 전체 트랜젝션을 안쓰는이유는
    // service에서 발생하는 정보로 만 DTO를 완성시켜서 내보내기위해서였음
    //  만약 트랜잭션을 전부열경우  controller에서 DTO을 만들떄 필요한 데이터를 조회할수도있기에
    // 영속성 콘텍스트를 아예 열지않고 fetch join으로만 완성시킴


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdWorkspaceIds = new ArrayList<>();

    @Test
    @DisplayName("생성한 팀과 참여한 팀만 최신순으로 조회한다")
    void listsOnlyCreatedAndJoinedWorkspacesNewestFirst() throws Exception {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        Workspace ownWorkspace = createWorkspace("내가 만든 팀", user);
        Workspace joinedWorkspace = createWorkspace("참여한 팀", otherUser);
        workspaceMemberRepository.saveAndFlush(new WorkspaceMember(joinedWorkspace, user));
        createWorkspace("다른 회원만 소속된 팀", otherUser);

        mockMvc.perform(get("/api/v1/workspaces").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(joinedWorkspace.getId()))
                .andExpect(jsonPath("$[0].name").value("참여한 팀"))
                .andExpect(jsonPath("$[0].createdBy").value(otherUser.getId()))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].id").value(ownWorkspace.getId()))
                .andExpect(jsonPath("$[1].name").value("내가 만든 팀"))
                .andExpect(jsonPath("$[1].createdBy").value(user.getId()));
    }

    @Test
    @DisplayName("소속이 없으면 다른 팀이 있어도 빈 목록을 반환한다")
    void returnsEmptyListForUserWithoutMemberships() throws Exception {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        createWorkspace("다른 회원의 팀", otherUser);

        mockMvc.perform(get("/api/v1/workspaces").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("로그인하지 않으면 목록 조회는 401이다")
    void returns401ForAnonymousWorkspaceListRequest() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private AppUser createUser() {
        AppUser user = appUserRepository.saveAndFlush(new AppUser(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                "workspace-list-test@example.com",
                true,
                "목록테스트회원"
        ));
        createdUserIds.add(user.getId());
        return user;
    }

    private Workspace createWorkspace(String name, AppUser creator) {
        Workspace workspace = workspaceRepository.saveAndFlush(new Workspace(name, creator));
        createdWorkspaceIds.add(workspace.getId());
        workspaceMemberRepository.saveAndFlush(new WorkspaceMember(workspace, creator));
        return workspace;
    }

    private OidcLoginRequestPostProcessor loginAs(AppUser user) {
        return oidcLogin().idToken(token -> token
                .claim("iss", user.getIssuer())
                .subject(user.getSubject()));
    }

    @AfterEach
    void cleansUpOnlyDataCreatedByThisTest() {
        for (Long workspaceId : createdWorkspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            workspaceRepository.deleteById(workspaceId);
        }
        for (Long userId : createdUserIds) {
            appUserRepository.deleteById(userId);
        }
    }


    @Test
    @DisplayName("로그인하지 않으면 팀을 생성할 수 없다")
    void rejectsAnonymousWorkspaceCreation() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name": "A팀"}
                            """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }



    @Test
    @DisplayName("CSRF 토큰이 없으면 팀을 생성할 수 없다")
    void rejectsWorkspaceCreationWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name": "A팀"}
                            """))
                .andExpect(status().isForbidden());
    }


}
