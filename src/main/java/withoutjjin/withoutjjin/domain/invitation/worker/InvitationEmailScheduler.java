package withoutjjin.withoutjjin.domain.invitation.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class InvitationEmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailScheduler.class);

    private final InvitationEmailWorker worker;

    public InvitationEmailScheduler(InvitationEmailWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            initialDelayString = "${invitation.mail.poll-delay:10s}",
            fixedDelayString = "${invitation.mail.poll-delay:10s}"
    )
    public void sendPendingInvitations() {
        try {
            worker.processPendingBatch();
        } catch (RuntimeException failure) {
            // 조회 단계에서 실패해도 다음 실행에서 다시 시도한다.
            // 토큰 등이 담길 수 있는 예외 메시지나 전체 예외는 기록하지 않는다.
            log.warn("초대 메일 스케줄러 실행 실패: errorType={}", failure.getClass().getSimpleName());
        }
    }
}
