package withoutjjin.withoutjjin.domain.invitation.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationSendStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);

    @Test
    void 첫_발송을_시작하면_처리중으로_변경하고_시작시각을_기록한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));

        invitation.startProcessing(NOW);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(invitation.getProcessingStartedAt()).isEqualTo(NOW);
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getRetryCount()).isZero();
    }

    @Test
    void 접수_성공이면_발송용_암호문을_정리하고_검증용_해시는_유지한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);

        invitation.markSubmitted();

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        assertThat(invitation.getEncryptedToken()).isNull();
        assertThat(invitation.getTokenHash()).isEqualTo("test-hash");
        assertThat(invitation.getProcessingStartedAt()).isNull();
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void 일시적_실패는_30초_후_재시도하고_그전에는_시작하지_못한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW, true);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(invitation.getNextRetryAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(invitation.getEncryptedToken()).isEqualTo("test-ciphertext");
        assertThat(invitation.getProcessingStartedAt()).isNull();
        assertThat(invitation.getRetryCount()).isZero();

        assertThatThrownBy(() -> invitation.startProcessing(NOW.plusSeconds(29)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getRetryCount()).isZero();

        invitation.startProcessing(NOW.plusSeconds(30));

        assertThat(invitation.getRetryCount()).isEqualTo(1L);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(invitation.getNextRetryAt()).isNull();
    }

    @Test
    void 재시도_세번이_모두_실패하면_중단하고_암호문을_정리한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        LocalDateTime now = NOW;
        invitation.startProcessing(now);
        invitation.markFailed(now, true);

        long[] delays = {30, 60, 120};
        for (int index = 0; index < delays.length; index++) {
            assertThat(invitation.getNextRetryAt()).isEqualTo(now.plusSeconds(delays[index]));
            now = invitation.getNextRetryAt();
            invitation.startProcessing(now);
            assertThat(invitation.getRetryCount()).isEqualTo(index + 1L);
            invitation.markFailed(now, true);
        }

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(invitation.getRetryCount()).isEqualTo(3L);
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
        assertThatThrownBy(() -> invitation.startProcessing(NOW.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 재시도에서_성공하면_횟수를_유지하고_암호문을_정리한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);
        invitation.markFailed(NOW, true);
        invitation.startProcessing(NOW.plusSeconds(30));

        invitation.markSubmitted();

        assertThat(invitation.getRetryCount()).isEqualTo(1L);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        assertThat(invitation.getEncryptedToken()).isNull();
        assertThat(invitation.getNextRetryAt()).isNull();
    }

    @Test
    void 재시도할_수_없는_실패는_즉시_종료한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW, false);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(invitation.getRetryCount()).isZero();
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    void 재시도_시각에_초대가_만료된다면_재시도를_예약하지_않는다() {
        Invitation invitation = invitationExpiringAt(NOW.plusSeconds(30));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW, true);

        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    void 처리_도중_초대가_만료되어도_재시도하지_않는다() {
        Invitation invitation = invitationExpiringAt(NOW.plusMinutes(1));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW.plusMinutes(1), true);

        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    void 이미_처리중이거나_접수된_발송을_다시_시작하지_못한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);
        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);

        invitation.markSubmitted();
        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료된_초대는_발송을_시작하지_못한다() {
        Invitation invitation = invitationExpiringAt(NOW);

        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
    }

    @Test
    void 발송을_시작하지_않고_성공이나_실패로_변경하지_못한다() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));

        assertThatThrownBy(invitation::markSubmitted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitation.markFailed(NOW, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
    }

    private Invitation invitationExpiringAt(LocalDateTime expiresAt) {
        // 발송 상태 규칙만 확인하므로 DB와 연관 엔티티는 사용하지 않는다.
        return new Invitation(null, null, "invitee@example.com", "test-hash",
                "test-ciphertext", expiresAt);
    }
}
