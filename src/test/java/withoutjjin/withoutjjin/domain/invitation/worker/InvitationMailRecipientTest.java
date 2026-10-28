package withoutjjin.withoutjjin.domain.invitation.worker;

import org.junit.jupiter.api.Test;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InvitationMailRecipientTest {

    @Test
    void 다른_수신자의_초대는_직접_작업번호로_호출해도_처리하지_않는다() {
        InvitationRepository repository = mock(InvitationRepository.class);
        Invitation invitation = mock(Invitation.class);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(invitation));
        when(invitation.getTargetEmail()).thenReturn("other@example.com");
        InvitationMailTransactionService service = new InvitationMailTransactionService(repository,"allowed@example.com");

        assertThat(service.claim(1L, LocalDateTime.now())).isEmpty();
        verify(invitation, never()).startProcessing(any());
    }

    @Test
    void 다른_수신자의_멈춘_작업도_상태를_변경하지_않는다() {
        InvitationRepository repository = mock(InvitationRepository.class);
        Invitation invitation = mock(Invitation.class);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(invitation));
        when(invitation.getTargetEmail()).thenReturn("other@example.com");
        InvitationMailTransactionService service = new InvitationMailTransactionService(repository,"allowed@example.com");
        LocalDateTime now=LocalDateTime.now();

        service.recoverTimedOut(1L,now.minusMinutes(5),now);
        verify(invitation, never()).markFailed(any(), anyBoolean());
    }
}
