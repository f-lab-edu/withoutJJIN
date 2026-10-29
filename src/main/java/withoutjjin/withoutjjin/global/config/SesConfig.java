package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.time.Duration;

@Configuration
public class SesConfig {

    @Bean
    public AwsCredentialsProvider sesCredentialsProvider(
            @Value("${aws.credentials.access-key-id:}") String accessKeyId,
            @Value("${aws.credentials.secret-access-key:}") String secretAccessKey
    ) {
        // 로컬 .env는 Spring 설정으로 읽었으므로 SDK에 직접 전달한다.
        if (accessKeyId.isBlank() != secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("AWS 액세스 키 ID와 비밀 액세스 키를 함께 설정해야 합니다.");
        }

        if (!accessKeyId.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }

        // 별도 키를 설정하지 않은 배포 환경에서는 EC2 IAM 역할 등을 사용할 수 있다.
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client(
            AwsCredentialsProvider sesCredentialsProvider,
            @Value("${aws.ses.region}") String region
    ) {
        if (region.isBlank()) {
            throw new IllegalArgumentException("SES에서 이메일을 인증한 AWS 리전을 설정해야 합니다.");
        }

        // 클라이언트를 만드는 것만으로 메일을 보내지는 않는다.
        // SES API 호출에 사용할 객체
        return SesV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(sesCredentialsProvider) // AWS 인증 정보를 어디서 받을지 지정
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)) // 요청 시도 하나의 시간 제한
                        .apiCallTimeout(Duration.ofSeconds(20))  // SDK 호출 전체의 시간 제한
                        // 재시도 횟수는 초대 작업자가 관리하므로 SDK에서는 한 번만 요청한다.
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build();
    }
}
