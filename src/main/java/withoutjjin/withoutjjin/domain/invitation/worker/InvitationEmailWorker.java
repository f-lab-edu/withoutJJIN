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
    private static final int BATCH_SIZE = 20; // 가져올데이터 갯수
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final InvitationMailTransactionService transactions;
    private final InvitationEmailSender emailSender;
    private final InvitationTokenCipher tokenCipher;
    private final InvitationTokenGenerator tokenGenerator;
    private final Clock clock;
    private final String pageUrl;

    public InvitationEmailWorker( //필요한 객체를 전달받고 초대 화면 주소를 검사
            InvitationMailTransactionService transactions, //??
            InvitationEmailSender emailSender,// 이메일 보내는 사람, 발신자
            InvitationTokenCipher tokenCipher,// 토큰암호화된것
            InvitationTokenGenerator tokenGenerator, // 토큰을 난수만들거나 해쉬함수 넣는것
            @Qualifier("invitationMailClock") Clock clock, //어느 지역에 시간을 읽을지 표시
            @Value("${invitation.mail.page-url}") String pageUrl // url형식
    ) {
        URI uri = URI.create(pageUrl);
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                // HTTPS은 걱정 ㄴㄴ 요청을 보내지않았기에 암호화되지않았음, 적절한 schema인가?? 부분
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

    public void processPendingBatch() { // 한꺼번에 대기 처리

//        이전에 처리를 시작했는데 (현재 시간 - PROCESSING_TIMEOUT), cutoff 기준으로 아직 진행중인 녀석들을 찾음
//        아직 PROCESSING인 작업을 찾음
//          → 시간 초과 작업으로 복구 처리
        LocalDateTime now = now();
        LocalDateTime cutoff = now.minus(PROCESSING_TIMEOUT); //
        for (Long id : transactions.findTimedOutIds(cutoff, BATCH_SIZE)) {
            try {
                transactions.recoverTimedOut(id, cutoff, now); //? ?/?
            } catch (RuntimeException failure) {
                logFailure(id, failure);
            }
        }

        for (Long id : transactions.findSendableIds(now(), BATCH_SIZE)) {
            try {
                //지금 발송할 수 있는 초대 처리
                processInvitation(id);
            } catch (RuntimeException failure) {
                // 한 작업의 DB 오류가 나머지 작업 처리를 막지 않도록 한다.
                logFailure(id, failure);
            }
        }
    }

    public void processInvitation(Long id)
    {  // 초대 한 건의 메일 발송 처리
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
            String token = tokenCipher.decrypt(task.encryptedToken()); // 복호화
            if (!tokenGenerator.hashToken(token).equals(task.tokenHash())) {
                throw new IllegalStateException("초대 토큰의 해시가 일치하지 않습니다.");
            }
            invitationLink = UriComponentsBuilder.fromUriString(pageUrl)
                    .replaceQueryParam("token", token).build().encode().toUriString();
            // 초대 링크만듬

        } catch (RuntimeException failure) {
            // 복호화·해시 오류는 같은 데이터로 반복해도 해결되지 않으므로 재시도하지 않는다.
            transactions.markFailed(task, now(), false);
            logFailure(id, failure); //어떤 초대에서 어떤 종류의 오류가 났는지 기록
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
