package withoutjjin.withoutjjin.domain.document.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectDeleter;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionTransactions.PreparedDeletion;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class S3ObjectDeletionWorker {

    private final S3ObjectDeletionTransactions transactions;
    private final S3ObjectDeleter deleter;

    // S3 응답을 기다리는 동안 DB 트랜잭션과 행 잠금을 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean process(Long taskId) {
        Optional<PreparedDeletion> claimed = transactions.claim(taskId);
        if (claimed.isEmpty()) {
            return false;
        }
        PreparedDeletion prepared = claimed.get();
        try {
            deleter.delete(prepared.objectKey());
            return transactions.complete(prepared);
        } catch (RuntimeException failure) {
            try {
                transactions.retry(prepared);
            } catch (RuntimeException retryFailure) {
                // 재시도 저장이 실패해도 작업은 남아 있고 점유 시간이 지나면 다시 조회된다.
                failure.addSuppressed(retryFailure);
            }
            throw failure;
        }
    }
}
