package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;


@Configuration
public class AwsCredentialsConfig {

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider(
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

}
