package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@Component
public class S3ObjectReader {

    private final S3Client s3Client;
    private final String bucket;

    public S3ObjectReader(
            S3Client s3Client,
            @Value("${aws.s3.bucket}") String bucket
    ) {
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
//          호출할 때 전달할 StoreFile.objectKey
                .build();

        return s3Client.headObject(request); // 	실제 S3 HeadObject 요청 실행
    }
}