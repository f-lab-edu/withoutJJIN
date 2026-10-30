package withoutjjin.withoutjjin.domain.invitation.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import withoutjjin.withoutjjin.domain.invitation.mail.InvitationEmailSender;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationEmailWorkerTest {

    @Mock private InvitationMailTransactionService transactions;
    @Mock private InvitationEmailSender emailSender;
    @Mock private InvitationTokenCipher tokenCipher;
    @Mock private InvitationTokenGenerator tokenGenerator;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 28, 12, 0);
    private final Clock clock = Clock.fixed(now.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    @Test
    @DisplayName("recoverTimedOut과 claim의 DB오류 후에도 후속 InvitationId를 처리한다")
    void continuesProcessingAfterRecoveryAndClaimDatabaseFailures() {
        LocalDateTime cutoff = now.minusMinutes(5);
        when(transactions.findTimedOutIds(cutoff, 20)).thenReturn(List.of(10L, 11L));
        doThrow(new IllegalStateException("test recovery failure"))
                .when(transactions).recoverTimedOut(10L, cutoff, now);
        when(transactions.findSendableIds(now, 20)).thenReturn(List.of(20L, 21L));
        when(transactions.claim(20L, now)).thenThrow(new IllegalStateException("test claim failure"));
        when(transactions.claim(21L, now)).thenReturn(Optional.empty());

        worker("https://app.example.com/invite").processPendingBatch();

        verify(transactions).recoverTimedOut(11L, cutoff, now);
        verify(transactions).claim(21L, now);
        verifyNoInteractions(emailSender);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/invite", "ftp://app.example.com/invite",
            "https://user:password@app.example.com/invite", "https://app.example.com/invite#fragment"
    })
    @DisplayName("잘못된 초대화면주소는 시작할 때 거부한다")
    void rejectsInvalidInvitationPageUrlAtStartup(String pageUrl) {
        assertThatThrownBy(() -> worker(pageUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    private InvitationEmailWorker worker(String pageUrl) {
        return new InvitationEmailWorker(transactions, emailSender, tokenCipher, tokenGenerator, clock, pageUrl);
    }
}
