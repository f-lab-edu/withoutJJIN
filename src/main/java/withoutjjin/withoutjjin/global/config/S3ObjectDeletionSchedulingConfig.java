package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "document.storage.deletion.worker", name = "enabled", havingValue = "true")
public class S3ObjectDeletionSchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler s3ObjectDeletionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("s3-object-deletion-");
        return scheduler;
    }

    @Bean
    public S3ObjectDeletionScheduler s3ObjectDeletionScheduler(
            S3ObjectDeletionBatchProcessor processor,
            @Value("${document.storage.deletion.worker.poll-delay:10s}") String pollDelay,
            @Value("${document.storage.deletion.worker.batch-size:5}") int batchSize
    ) {
        long delayMillis;
        try {
            delayMillis = DurationStyle.detectAndParse(pollDelay).toMillis();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IllegalArgumentException("S3 삭제 작업의 조회 간격은 1ms 이상의 시간 값이어야 합니다.", exception);
        }
        if (delayMillis < 1) {
            throw new IllegalArgumentException("S3 삭제 작업의 조회 간격은 1ms 이상이어야 합니다.");
        }
        return new S3ObjectDeletionScheduler(processor, batchSize);
    }
}
