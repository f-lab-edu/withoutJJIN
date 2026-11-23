package withoutjjin.withoutjjin.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("document.upload-completion.async")
public record UploadCompletionAsyncProperties(
        @DefaultValue("4") int threads,
        @DefaultValue("8") int queueCapacity,
        @DefaultValue("30s") Duration responseTimeout,
        @DefaultValue("12") int maxInFlight
) {
    public UploadCompletionAsyncProperties {
        if (threads < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("업로드 완료 스레드는 1개 이상, 대기열은 0개 이상이어야 합니다.");
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("업로드 완료 진행 중 요청 수는 1 이상이어야 합니다.");
        }
        try {
            if (responseTimeout == null || responseTimeout.toMillis() < 1) {
                throw new IllegalArgumentException("업로드 완료 응답 대기시간은 1ms 이상이어야 합니다.");
            }
            Math.addExact(responseTimeout.toMillis(), 5000L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("업로드 완료 응답 대기시간이 너무 큽니다.", exception);
        }
    }
}
