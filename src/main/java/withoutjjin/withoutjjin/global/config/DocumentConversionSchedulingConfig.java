package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "document.conversion.worker", name = "enabled", havingValue = "true")
public class DocumentConversionSchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler documentConversionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("document-conversion-");
        return scheduler;
    }

    @Bean
    public DocumentConversionScheduler documentConversionScheduler(
            DocumentConversionBatchProcessor processor,
            @Value("${document.conversion.worker.poll-delay:10s}") String pollDelay,
            @Value("${document.conversion.worker.batch-size:5}") int batchSize
    ) {
        long delayMillis;
        try {
            delayMillis = DurationStyle.detectAndParse(pollDelay).toMillis();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IllegalArgumentException("PDF 변환 조회 간격은 1ms 이상의 시간 값이어야 합니다.", exception);
        }
        if (delayMillis < 1) {
            throw new IllegalArgumentException("PDF 변환 조회 간격은 1ms 이상이어야 합니다.");
        }
        return new DocumentConversionScheduler(processor, batchSize);
    }
}
