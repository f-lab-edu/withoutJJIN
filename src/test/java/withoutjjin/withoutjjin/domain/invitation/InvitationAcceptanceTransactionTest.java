package withoutjjin.withoutjjin.domain.invitation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import withoutjjin.withoutjjin.domain.invitation.dto.InvitationAcceptanceResponse;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 테스트 트랜잭션을 열지 않고, 실제 서비스의 커밋·롤백과 동시 호출을 검사한다.
@SpringBootTest
@ActiveProfiles("test")
class InvitationAcceptanceTransactionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    @Autowired private InvitationAcceptanceService service;
    @Autowired private InvitationRepository invitations;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private InvitationTokenGenerator tokens;
    @Autowired private InvitationTokenCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private WorkspaceMemberRepository members;
    @MockitoBean(name = "invitationMailClock") private Clock clock;

    private final List<Long> userIds = new ArrayList<>();
    private Workspace workspace;
    private AppUser creator;
    private AppUser invitee;

    @BeforeEach
    void setUp() {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenReturn(NOW.atZone(ZONE).toInstant());
        creator = saveUser();
        invitee = saveUser();
        workspace = workspaces.saveAndFlush(new Workspace("수락 트랜잭션 테스트 팀", creator));
        members.saveAndFlush(new WorkspaceMember(workspace, creator));
    }

    @AfterEach
    void cleanUp() {
        // 이번 테스트가 만든 팀과 회원만 정리한다. 기존 개발 데이터에는 접근하지 않는다.
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM invitation WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Test
    @DisplayName("멤버 등록이 실패하면 이미 SQL로 반영한 초대 수락도 롤백한다")
    void rollsBackFlushedAcceptanceWhenMembershipSaveFails() {
        String token = saveInvitation();
        IllegalStateException failure = new IllegalStateException("test member insert failure");
        doAnswer(invocation -> {
            invitations.flush();
            assertThat(jdbc.queryForObject("SELECT invitation_status FROM invitation WHERE token_hash = ?",
                    String.class, tokens.hashToken(token))).isEqualTo("ACCEPTED");
            throw failure;
        }).when(members).saveAndFlush(any(WorkspaceMember.class));

        assertThatThrownBy(() -> accept(token)).isSameAs(failure);

        assertThat(jdbc.queryForObject("SELECT invitation_status FROM invitation WHERE token_hash = ?",
                String.class, tokens.hashToken(token))).isEqualTo("PENDING");
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), invitee.getId())).isFalse();
        assertThat(invitations.findByTokenHash(tokens.hashToken(token)).orElseThrow().getEncryptedToken()).isNotNull();
    }

    @Test
    @DisplayName("같은 초대를 동시에 수락해도 멤버는 한번만 등록한다")
    void createsOneMembershipWhenAcceptingSameInvitationConcurrently() throws Exception {
        String token = saveInvitation();
        acceptConcurrently(token, token);
        assertOneMembership();
    }

    @Test
    @DisplayName("같은 팀의 서로다른 초대를 동시에 수락해도 중복가입하지 않는다")
    void preventsDuplicateMembershipWhenAcceptingDifferentInvitationsConcurrently() throws Exception {
        String first = saveInvitation();
        String second = saveInvitation();
        acceptConcurrently(first, second);
        assertOneMembership();
    }

    private void acceptConcurrently(String first, String second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<InvitationAcceptanceResponse>> results = new ArrayList<>();
            for (String token : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test start timeout");
                    return accept(token);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<InvitationAcceptanceResponse> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).invitationStatus()).isEqualTo(InvitationStatus.ACCEPTED);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertOneMembership() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Long.class, workspace.getId(), invitee.getId())).isEqualTo(1L);
    }

    private InvitationAcceptanceResponse accept(String token) {
        return service.acceptInvitation(token, invitee.getIssuer(), invitee.getSubject(), invitee.getEmail(), true);
    }

    private AppUser saveUser() {
        String id = UUID.randomUUID().toString();
        AppUser user = users.saveAndFlush(new AppUser("https://accounts.google.com", id,
                id + "@example.com", true, "수락 동시성 테스트 회원"));
        userIds.add(user.getId());
        return user;
    }

    private String saveInvitation() {
        String token = tokens.generateToken();
        invitations.saveAndFlush(new Invitation(workspace, creator, invitee.getEmail(),
                tokens.hashToken(token), cipher.encrypt(token), NOW.plusHours(24)));
        return token;
    }
}
