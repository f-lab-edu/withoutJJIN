package withoutjjin.withoutjjin.domain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceMemberService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class WorkspaceMemberTransactionTest {
    @Autowired AppUserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @MockitoSpyBean WorkspaceMemberRepository members;
    @Autowired WorkspaceMemberService service;
    @Autowired JdbcTemplate jdbc;
    AppUser creator;
    AppUser target;
    Workspace workspace;

    @BeforeEach void setUp() {
        creator = user(); target = user();
        workspace = workspaces.saveAndFlush(new Workspace("강퇴 롤백 팀", creator));
        members.saveAndFlush(new WorkspaceMember(workspace, creator));
        members.saveAndFlush(new WorkspaceMember(workspace, target));
    }
    @AfterEach void cleanUp() {
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace_member_ban WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (creator != null) jdbc.update("DELETE FROM app_user WHERE id = ?", creator.getId());
        if (target != null) jdbc.update("DELETE FROM app_user WHERE id = ?", target.getId());
    }

    @Test void failedMemberRemovalRollsBackAlreadyInsertedBan() {
        IllegalStateException failure = new IllegalStateException("test flush failure");
        doThrow(failure).when(members).flush();
        assertThatThrownBy(() -> service.removeMember(workspace.getId(), target.getId(), creator.getIssuer(), creator.getSubject()))
                .isSameAs(failure);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspace.getId(), target.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member_ban WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspace.getId(), target.getId())).isZero();
    }

    private AppUser user() {
        String subject = UUID.randomUUID().toString();
        return users.saveAndFlush(new AppUser("https://accounts.google.com", subject, subject + "@example.invalid", true, "회원"));
    }
}
