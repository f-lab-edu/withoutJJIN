package withoutjjin.withoutjjin.domain.document.storage;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class S3UploadUrlGenerator {

    private final S3Presigner presigner; //업로드 요청에 서명해서 임시 URL 생성
    private final DocumentStorageKeyGenerator storageKeyGenerator; // 저장할 파일의 키생성

    @Value("${aws.s3.bucket}") //사용할 S3 버킷 이름
    private String bucket;

    @Value("${aws.s3.upload-url-expiration}") //생성할 임시 업로드 URL의 유효기간
    private String uploadUrlExpiration;
//    임시 URL을 가진 사람은 그 URL이 허용하는 위치에 파일을 올릴 수 있어.
//    그래서 주소가 오래 남거나 다른 사람에게 전달됐을 때도 업로드 권한이 계속 유지되면 곤란하지.


    public GeneratedUploadUrl generate(Long workspaceId) { //업로드를 위한 URL생성
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("S3 버킷 이름을 설정해야 합니다.");
        }

        // 1. S3에 저장할 파일의 키 생성
        String storageKey = storageKeyGenerator.generate(workspaceId);

        // 2. YAML의 "5m"을 5분이라는 시간 값으로 변환
        Duration expiration = DurationStyle.detectAndParse(uploadUrlExpiration);

        // 3. 어느 버킷의 어느 키에 업로드할지 지정
        PutObjectRequest uploadRequest = PutObjectRequest.builder()
                .bucket(bucket) // 버킷
                .key(storageKey) //
                // 기존 S3 객체가 있으면 덮어쓰지 못하도록 조건을 서명에 포함한다.
                .ifNoneMatch("*") //이미 있는 파일일경우 412에러발생  //  '*' 모든파일을 조사하라는 듯
                .build();

        // 4. 업로드 요청과 URL 유효기간을 묶음
        PutObjectPresignRequest signingRequest = // SDK가 서명을 계산해 임시 URL만들어줌
                PutObjectPresignRequest.builder()
                        .signatureDuration(expiration) //만료일
                        .putObjectRequest(uploadRequest) //
                        .build();


//        AWS의 액세스 키 ID와 비밀 액세스 키 = AWS 인증정보

        // 5. SDK가 AWS 인증 정보를 사용해 서명된 URL 생성
        PresignedPutObjectRequest result =
                presigner.presignPutObject(signingRequest);

        return new GeneratedUploadUrl(
                result.url().toString(),
                storageKey,
                result.expiration()
        );


    }






}
