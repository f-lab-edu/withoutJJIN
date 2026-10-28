package withoutjjin.withoutjjin.domain.invitation.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import withoutjjin.withoutjjin.domain.invitation.mail.InvitationEmailSender;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationMailTransactionService.MailTask;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
// 이미 열린 DB 트랜잭션 안에서 이 작업자를 호출하지 못하게 한다.
@Transactional(propagation = Propagation.NEVER)
public class InvitationEmailWorker {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailWorker.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final InvitationMailTransactionService transactions;
    private final InvitationEmailSender emailSender;
    private final InvitationTokenCipher tokenCipher;
    private final InvitationTokenGenerator tokenGenerator;
    private final Clock clock;
    private final String pageUrl;

    public InvitationEmailWorker(
            InvitationMailTransactionService transactions,
            InvitationEmailSender emailSender,
            InvitationTokenCipher tokenCipher,
            InvitationTokenGenerator tokenGenerator,
            @Qualifier("invitationMailClock") Clock clock,
            @Value("${invitation.mail.page-url}") String pageUrl
    ) {
        URI uri = URI.create(pageUrl);
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) // HTTPS은 걱정 ㄴㄴ 요청을 보내지않았기에 암호화되지않았음
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("초대 화면 주소는 올바른 HTTP 또는 HTTPS 주소여야 합니다.");
        }
        this.transactions = transactions;
        this.emailSender = emailSender;
        this.tokenCipher = tokenCipher;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.pageUrl = pageUrl;
    }

    public void processPendingBatch() {
        LocalDateTime now = now();
        LocalDateTime cutoff = now.minus(PROCESSING_TIMEOUT);
        for (Long id : transactions.findTimedOutIds(cutoff, BATCH_SIZE)) {
            try {
                transactions.recoverTimedOut(id, cutoff, now);
            } catch (RuntimeException failure) {
                logFailure(id, failure);
            }
        }

        for (Long id : transactions.findSendableIds(now(), BATCH_SIZE)) {
            try {
                processInvitation(id);
            } catch (RuntimeException failure) {
                // 한 작업의 DB 오류가 나머지 작업 처리를 막지 않도록 한다.
                logFailure(id, failure);
            }
        }
    }

    public void processInvitation(Long id) {
        Optional<MailTask> claimed = transactions.claim(id, now());
        if (claimed.isEmpty()) {
            return;
        }
        MailTask task = claimed.get();

        if (!task.expiresAt().isAfter(now())) {
            transactions.markFailed(task, now(), false);
            return;
        }

        String invitationLink;
        try {
            String token = tokenCipher.decrypt(task.encryptedToken());
            if (!tokenGenerator.hashToken(token).equals(task.tokenHash())) {
                throw new IllegalStateException("초대 토큰의 해시가 일치하지 않습니다.");
            }
            invitationLink = UriComponentsBuilder.fromUriString(pageUrl)
                    .replaceQueryParam("token", token).build().encode().toUriString();
        } catch (RuntimeException failure) {
            // 복호화·해시 오류는 같은 데이터로 반복해도 해결되지 않으므로 재시도하지 않는다.
            transactions.markFailed(task, now(), false);
            logFailure(id, failure);
            return;
        }

        try {
            // 처리 시작 트랜잭션이 이미 끝난 뒤 외부 API를 호출한다.
            emailSender.sendInvitation(task.targetEmail(), task.workspaceName(), invitationLink);
        } catch (SesV2Exception failure) {
            boolean retryable = failure.statusCode() == 429 || failure.statusCode() >= 500;
            transactions.markFailed(task, now(), retryable);
            logFailure(id, failure);
            return;
        } catch (SdkClientException failure) {
            // 응답을 확인할 수 없는 통신·클라이언트 오류는 횟수를 제한해 재시도한다.
            transactions.markFailed(task, now(), true);
            logFailure(id, failure);
            return;
        } catch (RuntimeException failure) {
            transactions.markFailed(task, now(), false);
            logFailure(id, failure);
            return;
        }

        // SES 성공 뒤 DB 기록이 실패한 경우, 발송 실패로 덮어쓰지 않는다.
        // PROCESSING이 남으면 다음 실행에서 시간 초과 작업으로 복구한다.
        transactions.markSubmitted(task);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private void logFailure(Long id, RuntimeException failure) {
        // 토큰·메일 본문이 예외 메시지에 포함될 수 있으므로 오류 종류만 기록한다.
        log.warn("초대 메일 작업 실패: invitationId={}, errorType={}", id, failure.getClass().getSimpleName());
    }
}
