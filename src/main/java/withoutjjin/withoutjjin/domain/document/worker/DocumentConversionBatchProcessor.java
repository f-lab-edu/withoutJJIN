package withoutjjin.withoutjjin.domain.document.worker;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;

@Component
@RequiredArgsConstructor
public class DocumentConversionBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentConversionBatchProcessor.class);

    private final DocumentConversionJobRepository jobs;
    private final DocumentConversionWorker worker;

    // 한 회차 전체를 DB 트랜잭션으로 감싸지 않는다. 작업별 트랜잭션은 Worker가 관리한다.
    @Transactional(propagation = Propagation.NEVER)
    public int processPendingBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("PDF 변환 작업의 조회 건수는 양수여야 합니다.");
        }

        int completed = 0;
        for (Long jobId : jobs.findPendingIds(PageRequest.of(0, batchSize))) {
            try {
                if (worker.process(jobId)) {
                    completed++;
                }
            } catch (RuntimeException failure) {
                // 문서 내용이 예외 메시지에 들어갈 수 있으므로 ID와 오류 종류만 기록한다.
                log.warn("PDF 변환 작업 실패: jobId={}, errorType={}", jobId, failure.getClass().getSimpleName());
            }
        }
        return completed;
    }
}
