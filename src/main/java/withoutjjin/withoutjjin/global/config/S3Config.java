package withoutjjin.withoutjjin.global.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {
    @Bean
//            (destroyMethod = "close") // 종료할때 호출할 메소드를 지정하는 설정 // "close" 메서드이름 지정
    public S3Presigner s3Presigner(
            @Qualifier("awsCredentialsProvider")
            AwsCredentialsProvider credentialsProvider, // credentials는 AWS인증 정보, provider는 제공자
            @Value("${aws.s3.region}") String region //
    ) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider) // 임시 Uri에다가 서명인증을하는 객체 라고보면됨
                .build();
    }
}
