package withoutjjin.withoutjjin.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("aws.s3.async-head")
public record S3AsyncHeadProperties(
        @DefaultValue("12") int maxConcurrency,
        @DefaultValue("12") int maxPendingConnectionAcquires,
        @DefaultValue("2") int eventLoopThreads,
        @DefaultValue("2s") Duration connectionTimeout,
        @DefaultValue("1s") Duration connectionAcquisitionTimeout
) {
    public S3AsyncHeadProperties {
        if (maxConcurrency < 1 || maxPendingConnectionAcquires < 1 || eventLoopThreads < 1) {
            throw new IllegalArgumentException("S3 비동기 동시 조회·연결 대기·I/O 스레드 수는 1 이상이어야 합니다.");
        }
        requirePositiveMillis(connectionTimeout);
        requirePositiveMillis(connectionAcquisitionTimeout);
    }

    private static void requirePositiveMillis(Duration duration) {
        try {
            if (duration == null || duration.toMillis() < 1) {
                throw new IllegalArgumentException("S3 비동기 연결 대기시간은 1ms 이상이어야 합니다.");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("S3 비동기 연결 대기시간이 너무 큽니다.", exception);
        }
    }
}
