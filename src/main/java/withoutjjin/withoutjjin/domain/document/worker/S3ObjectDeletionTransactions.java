package withoutjjin.withoutjjin.domain.document.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class S3ObjectDeletionTransactions {

    private final S3ObjectDeletionTaskRepository tasks;
    private final Clock clock;
    private final Duration claimLease;
    private final Duration retryDelay;

    public S3ObjectDeletionTransactions(
            S3ObjectDeletionTaskRepository tasks,
            @Qualifier("documentUploadClock") Clock clock,
            @Value("${document.storage.deletion.claim-lease:2m}") Duration claimLease,
            @Value("${document.storage.deletion.retry-delay:1m}") Duration retryDelay
    ) {
        requirePositiveDuration(claimLease, "S3 삭제 작업의 점유 시간");
        requirePositiveDuration(retryDelay, "S3 삭제 작업의 재시도 대기시간");
        this.tasks = tasks;
        this.clock = clock;
        this.claimLease = claimLease;
        this.retryDelay = retryDelay;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<PreparedDeletion> claim(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("S3ObjectDeletionTask ID는 양수여야 합니다.");
        }
        Optional<S3ObjectDeletionTask> found = tasks.findByIdForUpdate(taskId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (found.isEmpty() || !found.get().isReady(now)) {
            return Optional.empty();
        }
        S3ObjectDeletionTask task = found.get();
        String token = task.claim(now, claimLease);
        return Optional.of(new PreparedDeletion(task.getId(), task.getObjectKey(), token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean complete(PreparedDeletion prepared) {
        Optional<S3ObjectDeletionTask> found = tasks.findByIdForUpdate(prepared.taskId());
        if (found.isEmpty() || !found.get().hasClaim(prepared.claimToken())) {
            return false;
        }
        tasks.delete(found.get());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void retry(PreparedDeletion prepared) {
        tasks.findByIdForUpdate(prepared.taskId())
                .filter(task -> task.hasClaim(prepared.claimToken()))
                .ifPresent(task -> task.scheduleRetry(LocalDateTime.now(clock), retryDelay));
    }

    private static void requirePositiveDuration(Duration duration, String name) {
        try {
            if (duration != null && duration.toMillis() >= 1) {
                return;
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + "이 너무 큽니다.", exception);
        }
        throw new IllegalArgumentException(name + "은 1ms 이상이어야 합니다.");
    }

    // 트랜잭션 밖에서는 엔티티 대신 저장 키와 작업 점유 토큰만 사용한다.
    public record PreparedDeletion(Long taskId, String objectKey, String claimToken) {
    }
}
