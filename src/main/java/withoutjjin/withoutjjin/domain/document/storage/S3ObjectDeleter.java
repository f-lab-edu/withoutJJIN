package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@Component
public class S3ObjectDeleter {

    private final S3Client s3Client;
    private final String bucket;

    public S3ObjectDeleter(
            S3Client s3Client,
            @Value("${aws.s3.bucket}") String bucket
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("S3 버킷 이름을 설정해야 합니다.");
        }
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    // 권한 검사 후 서버에서 조회한 StoreFile.objectKey를 전달한다.
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("삭제할 파일의 S3 저장 키가 필요합니다.");
        }

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        // S3 오류는 호출한 쪽으로 전달하여 삭제 완료로 처리하지 않는다.
        s3Client.deleteObject(request);
    }
}
