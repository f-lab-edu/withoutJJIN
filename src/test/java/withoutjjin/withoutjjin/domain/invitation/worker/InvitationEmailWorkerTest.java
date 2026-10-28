package withoutjjin.withoutjjin.domain.invitation.worker;

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
    void 한_작업의_DB오류가_다른_작업의_처리를_막지_않는다() {
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
    void 잘못된_초대화면주소는_시작할_때_거부한다(String pageUrl) {
        assertThatThrownBy(() -> worker(pageUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    private InvitationEmailWorker worker(String pageUrl) {
        return new InvitationEmailWorker(transactions, emailSender, tokenCipher, tokenGenerator, clock, pageUrl);
    }
}
