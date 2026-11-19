package withoutjjin.withoutjjin.domain.document.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class DocumentConversionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentConversionScheduler.class);

    private final DocumentConversionBatchProcessor processor;
    private final int batchSize;

    public DocumentConversionScheduler(DocumentConversionBatchProcessor processor, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("PDF 변환 작업의 조회 건수는 양수여야 합니다.");
        }
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(
            scheduler = "documentConversionTaskScheduler",
            initialDelayString = "${document.conversion.worker.poll-delay:10s}",
            fixedDelayString = "${document.conversion.worker.poll-delay:10s}"
    )
    public void processPendingConversions() {
        try {
            processor.processPendingBatch(batchSize);
        } catch (RuntimeException failure) {
            // 조회 실패 후에도 다음 회차를 실행한다. 문서 내용이 담길 수 있는 예외 메시지는 기록하지 않는다.
            log.warn("PDF 변환 스케줄러 실행 실패: errorType={}", failure.getClass().getSimpleName());
        }
    }
}
