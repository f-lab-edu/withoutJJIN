package withoutjjin.withoutjjin.domain.invitation.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class InvitationMailTransactionService { //DB 조회와 상태 변경을 담당하는 다른 서비스

    private final InvitationRepository invitationRepository;
    private final String allowedRecipient;

    public InvitationMailTransactionService(
            InvitationRepository invitationRepository,
            @Value("${invitation.mail.allowed-recipient:}") String allowedRecipient
    ) {
        this.invitationRepository = invitationRepository;
        this.allowedRecipient = allowedRecipient.isBlank() ? null : allowedRecipient.trim();
    }

    //지금 발송할 수 있는 초대 번호 조회
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findSendableIds(LocalDateTime now, int limit) {
        return invitationRepository.findSendableIds(
                InvitationStatus.PENDING, EmailSendStatus.PENDING, EmailSendStatus.FAILED,
                now, allowedRecipient, PageRequest.of(0, limit)
        );
    }

    //너무 오래 PROCESSING으로 남은 초대 번호 조회
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findTimedOutIds(LocalDateTime cutoff, int limit) {
        return invitationRepository.findTimedOutProcessingIds(
                EmailSendStatus.PROCESSING, cutoff, allowedRecipient, PageRequest.of(0, limit)
        );
    }

    //초대 한 건을 확인하고 PROCESSING으로 변경
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MailTask> claim(Long id, LocalDateTime now) {
        Optional<Invitation> found = invitationRepository.findByIdForUpdate(id);
        if (found.isEmpty() || // 초대장이 비어있는지
                !isAllowed(found.get())  // 수령인이 맞는지 확인
                || !found.get().isReadyToSend(now) // 티켓이 준비상태인지 확인
        ) {
            return Optional.empty();
        }

        Invitation invitation = found.get();
        // MySQL DATETIME의 정밀도에 맞추고, 결과 기록 시 이 처리 시각을 함께 확인한다.
        invitation.startProcessing(now.withNano(0));
        return Optional.of(new MailTask(  // 초대필요한 -> DTO
                id,
                invitation.getTargetEmail(),
                invitation.getWorkspace().getName(),
                invitation.getEncryptedToken(),
                invitation.getTokenHash(),
                invitation.getExpiresAt(),
                invitation.getProcessingStartedAt()
        ));
    }

    //SES 접수 성공 기록
    @Transactional(propagation = Propagation.REQUIRES_NEW) // 트랜잭션안에 새로운 트랜잭션
    public void markSubmitted(MailTask task) {
        invitationRepository.findByIdForUpdate(
                task.id()).ifPresent(invitation -> {
            if (ownsProcessing(invitation, task)) { //초대장이랑 , dto가 맞는지 확인
                invitation.markSubmitted();// 상태 변환
            }
        });
    }

    //실패 기록 및 재시도 예약
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(MailTask task, LocalDateTime now, boolean retryable) {
        invitationRepository.findByIdForUpdate(task.id()).ifPresent(invitation -> {
            if (ownsProcessing(invitation, task))
            {
                invitation.markFailed(now, retryable);
            }
        });
    }



    //오래 멈춘 작업을 실패 처리하고 재시도 조건 적용
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    //너무 오래 PROCESSING으로 남아 있는 작업을 정리하고, 조건이 맞으면 재시도를 예약하는 메서드
    public void recoverTimedOut(Long id, LocalDateTime cutoff, LocalDateTime now) {
        invitationRepository.findByIdForUpdate(id).ifPresent(invitation -> {
            LocalDateTime startedAt = invitation.getProcessingStartedAt();
            if (isAllowed(invitation) && // 초대이메일 맞는지 확인
                    invitation.getEmailSendStatus() == EmailSendStatus.PROCESSING// 이메일 준비상태 확인
                    && startedAt != null && !startedAt.isAfter(cutoff) // 기간이 지났는지 확인
            ) {
                invitation.markFailed(now, true);
            }
        });
    }

    //전달받은 결과가 현재 DB의 처리 시도에 해당하는지 확인
    private boolean ownsProcessing(Invitation invitation, MailTask task) {
        return isAllowed(invitation) &&  // 지정된 이메일인지확인
                invitation.getEmailSendStatus() == EmailSendStatus.PROCESSING // 준비상태확인
                && Objects.equals(invitation.getProcessingStartedAt(), task.processingStartedAt() // 초대장이랑 DTO 시작시간이 같은지 확인
        );
    }

    //개발용으로 지정한 수신 주소인지 확인
    private boolean isAllowed(Invitation invitation) {
        return allowedRecipient == null ||  // 지정된 수령null인가 확인
                allowedRecipient.equalsIgnoreCase(invitation.getTargetEmail()); // 지정된 수령인이 목표한느 수려인인가?
    }

    // 엔티티 대신 발송에 필요한 값만 트랜잭션 밖으로 전달한다.
    public record MailTask(
            Long id,
            String targetEmail,
            String workspaceName,
            String encryptedToken,
            String tokenHash,
            LocalDateTime expiresAt,
            LocalDateTime processingStartedAt
    ) {
        @Override
        public String toString() {
            return "MailTask[id=" + id + "]";
        }
    }
}
