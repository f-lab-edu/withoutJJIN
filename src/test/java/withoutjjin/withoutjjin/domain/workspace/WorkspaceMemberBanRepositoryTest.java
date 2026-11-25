package withoutjjin.withoutjjin.domain.workspace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMemberBan;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberBanRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkspaceMemberBanRepositoryTest {
    @Autowired WorkspaceMemberBanRepository bans;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired AppUserRepository users;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    private Workspace workspace;
    private AppUser creator;
    private AppUser target;

    @BeforeEach
    void setUp() {
        creator = saveUser("생성자");
        target = saveUser("강퇴된 회원");
        workspace = workspaces.saveAndFlush(new Workspace("강퇴 저장소 테스트 팀", creator));
    }

    @Test
    @DisplayName("워크스페이스·대상 회원·강퇴한 생성자·강퇴 시각을 저장한다")
    void persistsBanAndRelationships() {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1);
        WorkspaceMemberBan ban = bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        em.clear();
        WorkspaceMemberBan found = bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(ban.getId());
        assertThat(found.getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(found.getUser().getId()).isEqualTo(target.getId());
        assertThat(found.getBannedBy().getId()).isEqualTo(creator.getId());
        assertThat(found.getCreatedAt()).isBetween(before, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isTrue();
        assertThat(bans.existsByWorkspaceIdAndUserId(workspace.getId(), creator.getId())).isFalse();
    }

    @Test
    @DisplayName("같은 워크스페이스·회원의 중복 차단은 DB 유일 제약으로 거부한다")
    void rejectsDuplicateWorkspaceUserPair() {
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        assertThatThrownBy(() -> bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 회원이라도 서로 다른 워크스페이스의 차단은 별도로 저장한다")
    void allowsSameUserInDifferentWorkspaces() {
        Workspace another = workspaces.saveAndFlush(new Workspace("다른 팀", creator));
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        bans.saveAndFlush(new WorkspaceMemberBan(another, target, creator));
        em.clear();
        assertThat(bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isPresent();
        assertThat(bans.findByWorkspaceIdAndUserId(another.getId(), target.getId())).isPresent();
    }

    @Test
    @DisplayName("같은 워크스페이스의 서로 다른 회원 차단을 저장할 수 있다")
    void allowsDifferentUsersInSameWorkspace() {
        AppUser another = saveUser("다른 회원");
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        bans.saveAndFlush(new WorkspaceMemberBan(workspace, another, creator));
        em.clear();
        assertThat(bans.findAllWithUsersByWorkspaceId(workspace.getId()))
                .extracting(ban -> ban.getUser().getId()).containsExactlyInAnyOrder(target.getId(), another.getId());
    }

    @Test
    @DisplayName("선택한 워크스페이스의 차단 목록만 최신순으로 조회하고 회원 정보를 함께 로딩한다")
    void listsOnlySelectedWorkspaceWithUsersAndDeterministicNewestOrder() {
        AppUser another = saveUser("두 번째 회원");
        WorkspaceMemberBan first = bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        WorkspaceMemberBan second = bans.saveAndFlush(new WorkspaceMemberBan(workspace, another, creator));
        Workspace otherWorkspace = workspaces.saveAndFlush(new Workspace("목록에서 제외할 팀", creator));
        bans.saveAndFlush(new WorkspaceMemberBan(otherWorkspace, target, creator));
        jdbc.update("UPDATE workspace_member_ban SET created_at = ? WHERE workspace_id = ?",
                LocalDateTime.of(2026, 11, 25, 12, 0), workspace.getId());
        em.clear();
        var found = bans.findAllWithUsersByWorkspaceId(workspace.getId());
        assertThat(found).extracting(WorkspaceMemberBan::getId).containsExactly(second.getId(), first.getId());
        for (WorkspaceMemberBan ban : found) {
            assertThat(Persistence.getPersistenceUtil().isLoaded(ban.getUser())).isTrue();
            assertThat(Persistence.getPersistenceUtil().isLoaded(ban.getBannedBy())).isTrue();
        }
    }

    @Test
    @DisplayName("차단 삭제는 Workspace·AppUser·기존 WorkspaceMember를 삭제하지 않는다")
    void removingBanDoesNotCascadeToWorkspaceUsersOrExistingMembership() {
        WorkspaceMember member = members.saveAndFlush(new WorkspaceMember(workspace, creator));
        WorkspaceMemberBan ban = bans.saveAndFlush(new WorkspaceMemberBan(workspace, target, creator));
        bans.delete(ban);
        bans.flush();
        em.clear();
        assertThat(bans.findByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isEmpty();
        assertThat(users.existsById(target.getId())).isTrue();
        assertThat(users.existsById(creator.getId())).isTrue();
        assertThat(workspaces.existsById(workspace.getId())).isTrue();
        assertThat(members.existsById(member.getId())).isTrue();
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), target.getId())).isFalse();
    }

    private AppUser saveUser(String name) {
        String unique = UUID.randomUUID().toString();
        return users.saveAndFlush(new AppUser("https://accounts.google.com", unique,
                unique + "@example.invalid", true, name));
    }
}
