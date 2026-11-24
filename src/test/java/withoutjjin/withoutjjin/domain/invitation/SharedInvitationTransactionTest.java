package withoutjjin.withoutjjin.domain.invitation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class SharedInvitationTransactionTest extends SharedInvitationFixture {
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WorkspaceMemberRepository memberRepository;
    @BeforeEach void setUp() { setUpFixture(); }

    @AfterEach void cleanUp() {
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM invitation WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAcceptanceThroughSameOrDifferentLinksCreatesOneMember(boolean differentLinks) throws Exception {
        String first = tokens.generateToken();
        String second = differentLinks ? tokens.generateToken() : first;
        saveInvitation(first);
        if (differentLinks) saveInvitation(second);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                String token = i % 2 == 0 ? first : second;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return acceptance.acceptInvitation(token, outsider.getIssuer(), outsider.getSubject()).workspaceId();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Long> result : results) assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(workspace.getId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspace.getId(), outsider.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitation WHERE workspace_id = ? AND invitation_status = 'ACTIVE' AND encrypted_token IS NOT NULL",
                Integer.class, workspace.getId())).isEqualTo(differentLinks ? 2 : 1);
    }

    @Test
    void failedMembershipInsertRollsBackAndLeavesReusableInvitation() {
        String token = tokens.generateToken();
        saveInvitation(token);
        IllegalStateException failure = new IllegalStateException("member insert failed");
        doThrow(failure).when(memberRepository).saveAndFlush(any(WorkspaceMember.class));
        assertThatThrownBy(() -> acceptance.acceptInvitation(token, outsider.getIssuer(), outsider.getSubject()))
                .isSameAs(failure);
        assertThat(members.existsByWorkspaceIdAndUserId(workspace.getId(), outsider.getId())).isFalse();
        assertThat(invitations.findByTokenHash(tokens.hashToken(token)).orElseThrow().getEncryptedToken()).isNotBlank();
        assertThat(acceptance.previewInvitation(token).workspaceId()).isEqualTo(workspace.getId());
    }
}
