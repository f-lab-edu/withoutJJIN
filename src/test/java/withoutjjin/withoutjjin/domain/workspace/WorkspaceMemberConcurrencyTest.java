package withoutjjin.withoutjjin.domain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.service.InvitationAcceptanceService;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceMemberService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(30)
@Import(WorkspaceMemberConcurrencyTest.LockObservationConfig.class)
class WorkspaceMemberConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMemberRepository members;
    @Autowired InvitationRepository invitations;
    @Autowired InvitationTokenGenerator tokens;
    @Autowired InvitationTokenCipher cipher;
    @Autowired InvitationAcceptanceService acceptance;
    @Autowired WorkspaceMemberService memberService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WorkspaceLockObserver lockObserver;

    private final List<Long> workspaceIds = new ArrayList<>();
    private AppUser creator;
    private AppUser target;
    private Workspace workspace;
    private String firstToken;
    private String secondToken;

    private enum Action { ACCEPT_FIRST, ACCEPT_SECOND, KICK, LEAVE, UNBAN }
    private enum Initial { MEMBER, LEFT, BANNED }
    private record Scenario(String name, Initial initial, Action first, Action second,
                            int firstStatus, int secondStatus, boolean member, boolean ban) {
        @Override public String toString() { return name; }
    }

    @BeforeEach
    void setUp() {
        creator = user(); target = user();
        workspace = workspace();
        members.saveAndFlush(new WorkspaceMember(workspace, target));
        firstToken = invitation(workspace);
        secondToken = invitation(workspace);
    }

    @AfterEach
    void cleanUp() {
        // 테스트 자체의 트랜잭션은 열지 않는다. 각 서비스 호출의 실제 커밋을 검증한다.
        for (Long id : workspaceIds) {
            jdbc.update("DELETE FROM invitation WHERE workspace_id = ?", id);
            jdbc.update("DELETE FROM workspace_member_ban WHERE workspace_id = ?", id);
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", id);
            jdbc.update("DELETE FROM workspace WHERE id = ?", id);
        }
        if (target != null) jdbc.update("DELETE FROM app_user WHERE id = ?", target.getId());
        if (creator != null) jdbc.update("DELETE FROM app_user WHERE id = ?", creator.getId());
    }

    static Stream<Scenario> orderedCases() {
        return Stream.of(
                new Scenario("강퇴 후 같은 URL 가입", Initial.MEMBER, Action.KICK, Action.ACCEPT_FIRST, 204, 403, false, true),
                new Scenario("강퇴 후 다른 URL 가입", Initial.MEMBER, Action.KICK, Action.ACCEPT_SECOND, 204, 403, false, true),
                new Scenario("같은 URL 반복 수락 후 강퇴", Initial.MEMBER, Action.ACCEPT_FIRST, Action.KICK, 200, 204, false, true),
                new Scenario("다른 URL 반복 수락 후 강퇴", Initial.MEMBER, Action.ACCEPT_SECOND, Action.KICK, 200, 204, false, true),
                new Scenario("신규 가입 커밋 후 강퇴", Initial.LEFT, Action.ACCEPT_FIRST, Action.KICK, 200, 204, false, true),
                new Scenario("비멤버 강퇴 거부 후 신규 가입", Initial.LEFT, Action.KICK, Action.ACCEPT_FIRST, 404, 200, true, false),
                new Scenario("본인 퇴장 후 재가입", Initial.MEMBER, Action.LEAVE, Action.ACCEPT_FIRST, 204, 200, true, false),
                new Scenario("가입 확인 후 본인 퇴장", Initial.MEMBER, Action.ACCEPT_FIRST, Action.LEAVE, 200, 204, false, false),
                new Scenario("차단 해제 후 가입", Initial.BANNED, Action.UNBAN, Action.ACCEPT_SECOND, 204, 200, true, false),
                new Scenario("차단 중 가입 거부 후 해제", Initial.BANNED, Action.ACCEPT_FIRST, Action.UNBAN, 403, 204, false, false),
                new Scenario("강퇴 후 본인 퇴장 거부", Initial.MEMBER, Action.KICK, Action.LEAVE, 204, 403, false, true),
                new Scenario("본인 퇴장 후 강퇴 대상 없음", Initial.MEMBER, Action.LEAVE, Action.KICK, 204, 404, false, false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderedCases")
    void waitingOperationObservesCommittedMembershipAndBan(Scenario scenario) throws Exception {
        prepare(scenario.initial());
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        lockObserver.secondAttempted = secondAttempted;
        ExecutorService firstExecutor = single("member-race-first");
        ExecutorService secondExecutor = single("member-race-second");
        try {
            Future<Integer> first = firstExecutor.submit(() -> executeHeld(scenario.first(), firstLocked, releaseFirst));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).as("첫 요청의 실제 Workspace 잠금 획득").isTrue();
            Future<Integer> second = secondExecutor.submit(() -> execute(scenario.second()));
            assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).as("두 번째 요청의 Workspace 잠금 시도").isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(scenario.firstStatus());
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(scenario.secondStatus());
        } finally {
            releaseFirst.countDown();
            shutdown(firstExecutor, secondExecutor);
        }
        assertState(workspace, scenario.member(), scenario.ban());
    }

    @Test
    void sixConcurrentKicksCreateOnlyOneBan() throws Exception {
        concurrently(Action.KICK);
        assertState(workspace, false, true);
        assertThat(jdbc.queryForObject("SELECT banned_by FROM workspace_member_ban WHERE workspace_id = ? AND user_id = ?",
                Long.class, workspace.getId(), target.getId())).isEqualTo(creator.getId());
    }

    @Test
    void sixConcurrentUnbansRemoveOnlyBanWithoutJoining() throws Exception {
        prepare(Initial.BANNED);
        concurrently(Action.UNBAN);
        assertState(workspace, false, false);
    }

    @Test
    void workspaceLockDoesNotBlockSameUserJoiningDifferentWorkspace() throws Exception {
        Workspace another = workspace();
        String otherToken = invitation(another);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        ExecutorService firstExecutor = single("member-race-first");
        ExecutorService otherExecutor = single("member-race-other-workspace");
        try {
            Future<Integer> kick = firstExecutor.submit(() -> executeHeld(Action.KICK, firstLocked, releaseFirst));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Long> join = otherExecutor.submit(() -> acceptance.acceptInvitation(otherToken,
                    target.getIssuer(), target.getSubject()).workspaceId());
            assertThat(join.get(5, TimeUnit.SECONDS)).isEqualTo(another.getId());
            assertThat(kick.isDone()).isFalse();
            releaseFirst.countDown();
            assertThat(kick.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            releaseFirst.countDown();
            shutdown(firstExecutor, otherExecutor);
        }
        assertState(workspace, false, true);
        assertState(another, true, false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockObservationConfig {
        @Bean WorkspaceLockObserver workspaceLockObserver() { return new WorkspaceLockObserver(); }
        @Bean HibernatePropertiesCustomizer observeWorkspaceLock(WorkspaceLockObserver observer) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", observer);
        }
    }

    static class WorkspaceLockObserver implements StatementInspector {
        volatile CountDownLatch secondAttempted = new CountDownLatch(1);
        @Override public String inspect(String sql) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            if (Thread.currentThread().getName().equals("member-race-second")
                    && normalized.contains(" from workspace ") && normalized.contains(" for update")) {
                secondAttempted.countDown();
            }
            return sql; // 실제 SQL과 잠금 동작은 그대로 실행한다.
        }
    }

    private int executeHeld(Action action, CountDownLatch firstLocked, CountDownLatch releaseFirst) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            return transaction.execute(status -> {
                Integer result = null;
                RuntimeException failure = null;
                try { result = perform(action); }
                catch (RuntimeException exception) { failure = exception; }
                // 서비스가 잠금과 변경을 수행한 뒤 실제 커밋/롤백 직전에서 요청 순서를 제어한다.
                firstLocked.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Transaction release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Transaction gate interrupted", exception);
                }
                if (failure != null) throw failure;
                return result;
            });
        } catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
    }

    private void concurrently(Action action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) results.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timeout");
                return execute(action);
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> result : results) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(204);
        } finally { start.countDown(); shutdown(executor); }
    }

    private int execute(Action action) {
        try { return perform(action); }
        catch (ResponseStatusException exception) { return exception.getStatusCode().value(); }
    }

    private int perform(Action action) {
        switch (action) {
            case ACCEPT_FIRST -> {acceptance.acceptInvitation(firstToken, target.getIssuer(), target.getSubject()); return 200;}
            case ACCEPT_SECOND -> {acceptance.acceptInvitation(secondToken, target.getIssuer(), target.getSubject()); return 200;}
            case KICK -> memberService.removeMember(workspace.getId(), target.getId(), creator.getIssuer(), creator.getSubject());
            case LEAVE -> memberService.leaveWorkspace(workspace.getId(), target.getIssuer(), target.getSubject());
            case UNBAN -> memberService.unbanMember(workspace.getId(), target.getId(), creator.getIssuer(), creator.getSubject());
        }
        return 204;
    }

    private void prepare(Initial initial) {
        if (initial == Initial.LEFT) memberService.leaveWorkspace(workspace.getId(), target.getIssuer(), target.getSubject());
        if (initial == Initial.BANNED) memberService.removeMember(workspace.getId(), target.getId(), creator.getIssuer(), creator.getSubject());
    }

    private void assertState(Workspace selected, boolean member, boolean ban) {
        int memberCount = jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, selected.getId(), target.getId());
        int banCount = jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member_ban WHERE workspace_id = ? AND user_id = ?",
                Integer.class, selected.getId(), target.getId());
        assertThat(memberCount).isEqualTo(member ? 1 : 0);
        assertThat(banCount).isEqualTo(ban ? 1 : 0);
        assertThat(memberCount + banCount).isLessThanOrEqualTo(1);
        assertThat(members.existsByWorkspaceIdAndUserId(selected.getId(), creator.getId())).isTrue();
        assertThat(users.existsById(target.getId())).isTrue();
    }

    private Workspace workspace() {
        Workspace saved = workspaces.saveAndFlush(new Workspace("멤버 동시성 팀", creator));
        workspaceIds.add(saved.getId());
        members.saveAndFlush(new WorkspaceMember(saved, creator));
        return saved;
    }
    private AppUser user() {
        String subject = UUID.randomUUID().toString();
        return users.saveAndFlush(new AppUser("https://accounts.google.com", subject, subject + "@example.invalid", true, "회원"));
    }
    private String invitation(Workspace selected) {
        String token = tokens.generateToken();
        invitations.saveAndFlush(new Invitation(selected, creator, tokens.hashToken(token), cipher.encrypt(token),
                LocalDateTime.now(ZoneId.of("Asia/Seoul"))));
        return token;
    }
    private ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(task, name));
    }
    private void shutdown(ExecutorService... executors) throws InterruptedException {
        for (ExecutorService executor : executors) executor.shutdownNow();
        for (ExecutorService executor : executors) assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
}
