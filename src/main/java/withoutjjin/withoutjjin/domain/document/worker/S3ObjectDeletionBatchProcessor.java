package withoutjjin.withoutjjin.domain.document.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class S3ObjectDeletionBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectDeletionBatchProcessor.class);

    private final S3ObjectDeletionTaskRepository tasks;
    private final S3ObjectDeletionWorker worker;
    private final Clock clock;

    public S3ObjectDeletionBatchProcessor(S3ObjectDeletionTaskRepository tasks,
                                           S3ObjectDeletionWorker worker,
                                           @Qualifier("documentUploadClock") Clock clock) {
        this.tasks = tasks;
        this.worker = worker;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public int processPendingBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("S3 삭제 작업의 조회 건수는 양수여야 합니다.");
        }
        int completed = 0;
        for (Long taskId : tasks.findReadyIds(LocalDateTime.now(clock), PageRequest.of(0, batchSize))) {
            try {
                if (worker.process(taskId)) {
                    completed++;
                }
            } catch (RuntimeException failure) {
                // 저장 키나 문서명이 담길 수 있는 원격 오류 본문은 로그에 남기지 않는다.
                log.warn("S3 파일 삭제 작업 실패: taskId={}, errorType={}", taskId, failure.getClass().getSimpleName());
            }
        }
        return completed;
    }
}
