package withoutjjin.withoutjjin.domain.document.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class S3ObjectDeletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectDeletionScheduler.class);

    private final S3ObjectDeletionBatchProcessor processor;
    private final int batchSize;

    public S3ObjectDeletionScheduler(S3ObjectDeletionBatchProcessor processor, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("S3 삭제 작업의 조회 건수는 양수여야 합니다.");
        }
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(
            scheduler = "s3ObjectDeletionTaskScheduler",
            initialDelayString = "${document.storage.deletion.worker.poll-delay:10s}",
            fixedDelayString = "${document.storage.deletion.worker.poll-delay:10s}"
    )
    public void processPendingDeletions() {
        try {
            processor.processPendingBatch(batchSize);
        } catch (RuntimeException failure) {
            log.warn("S3 파일 삭제 스케줄러 실행 실패: errorType={}", failure.getClass().getSimpleName());
        }
    }
}
