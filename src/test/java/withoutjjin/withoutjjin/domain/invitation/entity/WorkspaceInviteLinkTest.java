package withoutjjin.withoutjjin.domain.invitation.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class WorkspaceInviteLinkTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final String HASH = "a".repeat(64);
    private final AppUser creator = new AppUser("issuer", "subject", "creator@example.com", true, "생성자");
    private final Workspace workspace = new Workspace("공유 링크 팀", creator);

    @Test
    void repeatedValidityChecksDoNotConsumeLink() {
        WorkspaceInviteLink link = link();
        assertThat(link.isActiveAt(CREATED.plusMinutes(1))).isTrue();
        assertThat(link.isActiveAt(CREATED.plusMinutes(2))).isTrue();
        assertThat(link.getRevokedAt()).isNull();
        assertThat(link.getTokenHash()).isEqualTo(HASH);
    }

    @Test
    void expiresAtExactDeadline() {
        WorkspaceInviteLink link = link();
        assertThat(link.isActiveAt(CREATED.plusHours(24).minusNanos(1))).isTrue();
        assertThat(link.isActiveAt(CREATED.plusHours(24))).isFalse();
        assertThat(link.isActiveAt(CREATED.plusHours(25))).isFalse();
    }

    @Test
    void revocationPreservesFirstRevocationTimeAndHash() {
        WorkspaceInviteLink link = link();
        LocalDateTime revoked = CREATED.plusMinutes(1);
        link.revoke(revoked);
        link.revoke(revoked.plusMinutes(1));
        assertThat(link.isActiveAt(revoked)).isFalse();
        assertThat(link.getRevokedAt()).isEqualTo(revoked);
        assertThat(link.getTokenHash()).isEqualTo(HASH);
        assertThat(link.getExpiresAt()).isEqualTo(CREATED.plusHours(24));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "raw-url-token", "abcdef", "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG"})
    void rejectsValuesThatAreNotSha256Hex(String hash) {
        assertThatThrownBy(() -> new WorkspaceInviteLink(workspace, creator, hash, CREATED, CREATED.plusHours(24)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsExpiryAtOrBeforeCreation(int hours) {
        assertThatThrownBy(() -> new WorkspaceInviteLink(workspace, creator, HASH, CREATED, CREATED.plusHours(hours)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRelationsAndTimes() {
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceInviteLink(null, creator, HASH, CREATED, CREATED.plusHours(24)));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceInviteLink(workspace, null, HASH, CREATED, CREATED.plusHours(24)));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceInviteLink(workspace, creator, HASH, null, CREATED.plusHours(24)));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceInviteLink(workspace, creator, HASH, CREATED, null));
        assertThatNullPointerException().isThrownBy(() -> link().isActiveAt(null));
        assertThatNullPointerException().isThrownBy(() -> link().revoke(null));
    }

    private WorkspaceInviteLink link() {
        return new WorkspaceInviteLink(workspace, creator, HASH, CREATED, CREATED.plusHours(24));
    }
}
