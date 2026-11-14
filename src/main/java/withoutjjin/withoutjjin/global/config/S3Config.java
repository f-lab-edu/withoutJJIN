package withoutjjin.withoutjjin.global.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {


    @Bean
    public S3Presigner s3Presigner( //	브라우저가 S3에 요청할 수 있도록 서명된 URL을 생성
            @Qualifier("awsCredentialsProvider")
            AwsCredentialsProvider credentialsProvider,
            //	AWS 요청 인증에 사용할 Access Key ID, Secret Access Key 등의 인증 정보를 제공
            @Value("${aws.s3.region}") String region
            //	S3 API를 호출할 AWS 리전을 지정. 현재는 ap-northeast-2
    ) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider) // 임시 Uri에다가 서명인증을하는 객체 라고보면됨
                .build();
    }


    @Bean
    public S3Client s3Client( //우리 Spring 서버가 S3 API를 호출할 때 사용하는 AWS SDK 객체
            @Qualifier("awsCredentialsProvider")
            AwsCredentialsProvider credentialsProvider,
            //	AWS 요청 인증에 사용할 Access Key ID, Secret Access Key 등의 인증 정보를 제공
            @Value("${aws.s3.region}") String region
    ) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }






}
