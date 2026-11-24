package withoutjjin.withoutjjin.domain.invitation.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class InvitationTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 25, 12, 0);
    private final AppUser creator = new AppUser("issuer", "subject", "creator@example.com", true, "생성자");
    private final Workspace workspace = new Workspace("팀", creator);

    @Test
    void expiresExactlySevenDaysAfterCreation() {
        Invitation invitation = invitation();
        assertThat(invitation.getExpiresAt()).isEqualTo(now.plusDays(7));
        assertThat(invitation.isActiveAt(now.plusDays(7).minusNanos(1))).isTrue();
        assertThat(invitation.isActiveAt(now.plusDays(7))).isFalse();
        assertThat(invitation.isActiveAt(now.plusDays(7).plusSeconds(1))).isFalse();
    }

    @Test
    void repeatedRevocationKeepsFirstTimeAndRemovesCiphertext() {
        Invitation invitation = invitation();
        invitation.revoke(now.plusHours(1));
        invitation.revoke(now.plusHours(2));
        assertThat(invitation.getRevokedAt()).isEqualTo(now.plusHours(1));
        assertThat(invitation.getEncryptedToken()).isNull();
        assertThat(invitation.getInvitationStatus()).isEqualTo(InvitationStatus.CANCELED);
        assertThat(invitation.isActiveAt(now.plusHours(2))).isFalse();
        assertThat(invitation.getExpiresAt()).isEqualTo(now.plusDays(7));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"plain-token", "xyz", "ABCDEF"})
    void rejectsNonHashToken(String hash) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Invitation(workspace, creator, hash, "v1:cipher", now));
    }

    private Invitation invitation() {
        return new Invitation(workspace, creator, "a".repeat(64), "v1:cipher", now);
    }
}
