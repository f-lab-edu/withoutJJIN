package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class S3AsyncObjectReader {

    private final S3AsyncClient s3Client;
    private final String bucket;
    private final Duration callTimeout;
    private final Duration attemptTimeout;

    public S3AsyncObjectReader(
            @Qualifier("s3HeadAsyncClient") S3AsyncClient s3Client,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.s3.head-api-call-timeout:10s}") Duration callTimeout,
            @Value("${aws.s3.head-api-call-attempt-timeout:3s}") Duration attemptTimeout
    ) {
        if (callTimeout == null || attemptTimeout == null
                || callTimeout.isZero() || callTimeout.isNegative()
                || attemptTimeout.isZero() || attemptTimeout.isNegative()
                || attemptTimeout.compareTo(callTimeout) > 0) {
            throw new IllegalArgumentException("S3 HeadObject 시간 제한은 양수이며 전체 제한이 단일 시도 제한 이상이어야 합니다.");
        }
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.callTimeout = callTimeout;
        this.attemptTimeout = attemptTimeout;
    }

    public CompletableFuture<HeadObjectResponse> head(String objectKey) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .overrideConfiguration(config -> config.apiCallTimeout(callTimeout)
                        .apiCallAttemptTimeout(attemptTimeout))
                .build();
        try {
            return s3Client.headObject(request);
        } catch (RuntimeException failure) {
            // SDK가 Future 반환 전에 실패해도 호출자는 동일하게 Future의 실패로 처리한다.
            return CompletableFuture.failedFuture(failure);
        }
    }
}
