package withoutjjin.withoutjjin.domain.invitation.worker;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class InvitationMailTransactionService {

    private final InvitationRepository invitationRepository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findSendableIds(LocalDateTime now, int limit) {
        return invitationRepository.findSendableIds(
                InvitationStatus.PENDING, EmailSendStatus.PENDING, EmailSendStatus.FAILED,
                now, PageRequest.of(0, limit)
        );
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findTimedOutIds(LocalDateTime cutoff, int limit) {
        return invitationRepository.findTimedOutProcessingIds(
                EmailSendStatus.PROCESSING, cutoff, PageRequest.of(0, limit)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MailTask> claim(Long id, LocalDateTime now) {
        Optional<Invitation> found = invitationRepository.findByIdForUpdate(id);
        if (found.isEmpty() || !found.get().isReadyToSend(now)) {
            return Optional.empty();
        }

        Invitation invitation = found.get();
        // MySQL DATETIME의 정밀도에 맞추고, 결과 기록 시 이 처리 시각을 함께 확인한다.
        invitation.startProcessing(now.withNano(0));
        return Optional.of(new MailTask(
                id, invitation.getTargetEmail(), invitation.getWorkspace().getName(),
                invitation.getEncryptedToken(), invitation.getTokenHash(),
                invitation.getExpiresAt(), invitation.getProcessingStartedAt()
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(MailTask task) {
        invitationRepository.findByIdForUpdate(task.id()).ifPresent(invitation -> {
            if (ownsProcessing(invitation, task)) {
                invitation.markSubmitted();
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(MailTask task, LocalDateTime now, boolean retryable) {
        invitationRepository.findByIdForUpdate(task.id()).ifPresent(invitation -> {
            if (ownsProcessing(invitation, task)) {
                invitation.markFailed(now, retryable);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverTimedOut(Long id, LocalDateTime cutoff, LocalDateTime now) {
        invitationRepository.findByIdForUpdate(id).ifPresent(invitation -> {
            LocalDateTime startedAt = invitation.getProcessingStartedAt();
            if (invitation.getEmailSendStatus() == EmailSendStatus.PROCESSING
                    && startedAt != null && !startedAt.isAfter(cutoff)) {
                invitation.markFailed(now, true);
            }
        });
    }

    private boolean ownsProcessing(Invitation invitation, MailTask task) {
        return invitation.getEmailSendStatus() == EmailSendStatus.PROCESSING
                && Objects.equals(invitation.getProcessingStartedAt(), task.processingStartedAt());
    }

    // 엔티티 대신 발송에 필요한 값만 트랜잭션 밖으로 전달한다.
    public record MailTask(
            Long id, String targetEmail, String workspaceName,
            String encryptedToken, String tokenHash,
            LocalDateTime expiresAt, LocalDateTime processingStartedAt
    ) {
        @Override
        public String toString() {
            return "MailTask[id=" + id + "]";
        }
    }
}
