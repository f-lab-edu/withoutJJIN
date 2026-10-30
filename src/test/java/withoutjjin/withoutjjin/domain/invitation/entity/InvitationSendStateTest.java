package withoutjjin.withoutjjin.domain.invitation.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationSendStateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);

    @Test
    @DisplayName("첫 발송을 시작하면 처리중으로 변경하고 시작시각을 기록한다")
    void recordsProcessingStateAndStartTimeForFirstAttempt() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));

        invitation.startProcessing(NOW);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(invitation.getProcessingStartedAt()).isEqualTo(NOW);
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("접수 성공이면 발송용 암호문을 정리하고 검증용 해시는 유지한다")
    void clearsEncryptedTokenAndPreservesHashAfterSubmission() {
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
    @DisplayName("일시적 실패는 30초 후 재시도하고 그전에는 시작하지 못한다")
    void schedulesRetryAfterThirtySecondsAndRejectsEarlyAttempt() {
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
    @DisplayName("재시도 세번이 모두 실패하면 중단하고 암호문을 정리한다")
    void stopsAndClearsEncryptedTokenAfterThreeFailedRetries() {
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
    @DisplayName("재시도에서 성공하면 횟수를 유지하고 암호문을 정리한다")
    void preservesRetryCountAndClearsEncryptedTokenAfterSuccessfulRetry() {
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
    @DisplayName("재시도할 수 없는 실패는 즉시 종료한다")
    void stopsImmediatelyAfterNonRetryableFailure() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW, false);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(invitation.getRetryCount()).isZero();
        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    @DisplayName("재시도 시각에 초대가 만료된다면 재시도를 예약하지 않는다")
    void doesNotScheduleRetryAtOrAfterInvitationExpiry() {
        Invitation invitation = invitationExpiringAt(NOW.plusSeconds(30));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW, true);

        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    @DisplayName("처리 도중 초대가 만료되어도 재시도하지 않는다")
    void doesNotRetryWhenInvitationExpiresDuringProcessing() {
        Invitation invitation = invitationExpiringAt(NOW.plusMinutes(1));
        invitation.startProcessing(NOW);

        invitation.markFailed(NOW.plusMinutes(1), true);

        assertThat(invitation.getNextRetryAt()).isNull();
        assertThat(invitation.getEncryptedToken()).isNull();
    }

    @Test
    @DisplayName("이미 처리중이거나 접수된 발송을 다시 시작하지 못한다")
    void rejectsStartingProcessingOrSubmittedMailAgain() {
        Invitation invitation = invitationExpiringAt(NOW.plusDays(1));
        invitation.startProcessing(NOW);
        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);

        invitation.markSubmitted();
        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("만료된 초대는 발송을 시작하지 못한다")
    void rejectsStartingExpiredInvitation() {
        Invitation invitation = invitationExpiringAt(NOW);

        assertThatThrownBy(() -> invitation.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
    }

    @Test
    @DisplayName("발송을 시작하지 않고 성공이나 실패로 변경하지 못한다")
    void rejectsRecordingResultBeforeStarting() {
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
