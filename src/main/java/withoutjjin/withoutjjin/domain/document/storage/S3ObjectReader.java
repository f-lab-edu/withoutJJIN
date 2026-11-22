package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.time.Duration;

@Component
public class S3ObjectReader {

    private static final int MAX_DOCX_BYTES = 20 * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucket;
    private final Duration headCallTimeout;
    private final Duration headAttemptTimeout;

    public S3ObjectReader(
            S3Client s3Client,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.s3.head-api-call-timeout:10s}") Duration headCallTimeout,
            @Value("${aws.s3.head-api-call-attempt-timeout:3s}") Duration headAttemptTimeout
    ) {
        if (headCallTimeout == null || headAttemptTimeout == null
                || headCallTimeout.isZero() || headCallTimeout.isNegative()
                || headAttemptTimeout.isZero() || headAttemptTimeout.isNegative()
                || headAttemptTimeout.compareTo(headCallTimeout) > 0) {
            throw new IllegalArgumentException("S3 HeadObject 시간 제한은 양수이며 전체 제한이 단일 시도 제한 이상이어야 합니다.");
        }
        this.headCallTimeout = headCallTimeout;
        this.headAttemptTimeout = headAttemptTimeout;
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public HeadObjectResponse head(String objectKey) {
        // 	반환값 : S3가 반환한 파일 크기·Content-Type 등을 담는 AWS SDK 클래스
        HeadObjectRequest request = HeadObjectRequest.builder()
        //	조회할 버킷과 S3 저장 키를 담는 AWS SDK 클래스
                .bucket(bucket)
//          aws.s3.bucket에 설정한 S3 버킷 이름
                .key(objectKey)
                .overrideConfiguration(config -> config.apiCallTimeout(headCallTimeout)
                        .apiCallAttemptTimeout(headAttemptTimeout))
//          호출할 때 전달할 StoreFile.objectKey
                .build();

        return s3Client.headObject(request); // 	실제 S3 HeadObject 요청 실행
    }

    public byte[] downloadDocx(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            try {
                Long contentLength = response.response().contentLength();
                if (contentLength != null && contentLength > MAX_DOCX_BYTES) {
                    throw new IllegalStateException("S3 DOCX 데이터가 20MiB 제한을 초과했습니다.");
                }
                // Content-Length와 별개로 실제 읽는 크기도 제한한다.
                byte[] content = response.readNBytes(MAX_DOCX_BYTES + 1);
                if (content.length > MAX_DOCX_BYTES) {
                    throw new IllegalStateException("S3 DOCX 데이터가 20MiB 제한을 초과했습니다.");
                }
                return content;
            } catch (IOException | RuntimeException exception) {
                // close()에서 남은 본문을 계속 읽지 않도록 실패한 다운로드를 중단한다.
                response.abort();
                throw exception;
            }
        } catch (IOException exception) {
            throw SdkClientException.create("S3 DOCX 데이터를 읽지 못했습니다.", exception);
        }
    }
}
