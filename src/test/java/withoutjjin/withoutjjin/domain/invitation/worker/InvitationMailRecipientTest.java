package withoutjjin.withoutjjin.domain.invitation.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvitationMailRecipientTest {

    private static final long INVITATION_ID = 1L;
    private static final String ALLOWED_RECIPIENT = "allowed@example.com";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    private final InvitationRepository invitationRepository = mock(InvitationRepository.class);
    private final InvitationMailTransactionService service =
            new InvitationMailTransactionService(invitationRepository, ALLOWED_RECIPIENT);

    @Test
    @DisplayName("claim은 targetEmail이 allowedRecipient와 다르면 InvitationId로 호출해도 발송을 시작하지 않는다")
    void doesNotClaimInvitationForDifferentRecipient() {
        Invitation invitation = invitationWithTargetEmail("other@example.com");
        when(invitationRepository.findByIdForUpdate(INVITATION_ID)).thenReturn(Optional.of(invitation));
        assertThat(invitation.isReadyToSend(NOW)).isTrue();

        assertThat(service.claim(INVITATION_ID, NOW)).isEmpty();

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
        assertThat(invitation.getProcessingStartedAt()).isNull();
    }

    @Test
    @DisplayName("claim은 targetEmail이 allowedRecipient와 같으면 PROCESSING으로 변경하고 MailTask를 반환한다")
    void claimsAllowedRecipientAndReturnsProcessingTask() {
        Invitation invitation = invitationWithTargetEmail(ALLOWED_RECIPIENT);
        when(invitationRepository.findByIdForUpdate(INVITATION_ID)).thenReturn(Optional.of(invitation));

        InvitationMailTransactionService.MailTask mailTask = service.claim(INVITATION_ID, NOW).orElseThrow();

        assertThat(mailTask.id()).isEqualTo(INVITATION_ID);
        assertThat(mailTask.targetEmail()).isEqualTo(ALLOWED_RECIPIENT);
        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(invitation.getProcessingStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("recoverTimedOut은 targetEmail이 allowedRecipient와 다르면 시간초과된 PROCESSING을 유지한다")
    void preservesTimedOutProcessingForDifferentRecipient() {
        Invitation invitation = invitationWithTargetEmail("other@example.com");
        LocalDateTime processingStartedAt = NOW.minusMinutes(6);
        invitation.startProcessing(processingStartedAt);
        when(invitationRepository.findByIdForUpdate(INVITATION_ID)).thenReturn(Optional.of(invitation));

        service.recoverTimedOut(INVITATION_ID, NOW.minusMinutes(5), NOW);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(invitation.getProcessingStartedAt()).isEqualTo(processingStartedAt);
        assertThat(invitation.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("recoverTimedOut은 targetEmail이 allowedRecipient와 같으면 시간초과된 PROCESSING을 FAILED로 변경한다")
    void marksTimedOutProcessingAsFailedForAllowedRecipient() {
        Invitation invitation = invitationWithTargetEmail(ALLOWED_RECIPIENT);
        invitation.startProcessing(NOW.minusMinutes(6));
        when(invitationRepository.findByIdForUpdate(INVITATION_ID)).thenReturn(Optional.of(invitation));

        service.recoverTimedOut(INVITATION_ID, NOW.minusMinutes(5), NOW);

        assertThat(invitation.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(invitation.getProcessingStartedAt()).isNull();
        assertThat(invitation.getNextRetryAt()).isEqualTo(NOW.plusSeconds(30));
    }

    private Invitation invitationWithTargetEmail(String targetEmail) {
        return new Invitation(new Workspace("초대 발송 테스트 워크스페이스", null), null,
                targetEmail, "test-hash", "test-ciphertext", NOW.plusDays(1));
    }
}
