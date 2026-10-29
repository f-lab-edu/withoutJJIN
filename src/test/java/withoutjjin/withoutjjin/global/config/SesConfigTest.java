package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;

class SesConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SesConfig.class)
            .withPropertyValues("aws.ses.region=ap-northeast-2");

    @Test
    @DisplayName("설정에서 인증정보와 리전을 읽어 SES 클라이언트를 생성한다")
    void createsSesClientFromConfiguredCredentialsAndRegion() {
        // 실제 AWS 키나 네트워크 호출을 사용하지 않는다.
        contextRunner.withPropertyValues(
                "aws.credentials.access-key-id=local-test-key-id",
                "aws.credentials.secret-access-key=local-test-secret"
        ).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SesV2Client.class);
            AwsCredentials credentials = context.getBean(AwsCredentialsProvider.class).resolveCredentials();
            assertThat(credentials.accessKeyId()).isEqualTo("local-test-key-id");
            assertThat(credentials.secretAccessKey()).isEqualTo("local-test-secret");
            assertThat(context.getBean(SesV2Client.class).serviceClientConfiguration().region())
                    .isEqualTo(Region.AP_NORTHEAST_2);
        });
    }

    @Test
    @DisplayName("키를 직접 설정하지 않으면 기본 인증정보 탐색을 사용한다")
    void usesDefaultCredentialsProviderWithoutExplicitKeys() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SesV2Client.class);
            assertThat(context.getBean(AwsCredentialsProvider.class))
                    .isInstanceOf(DefaultCredentialsProvider.class);
            // resolveCredentials()를 호출하지 않아 IAM 메타데이터 등 외부 통신을 하지 않는다.
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aws.credentials.access-key-id=local-test-key-id",
            "aws.credentials.secret-access-key=local-test-secret"
    })
    @DisplayName("키 두개 중 하나만 설정하면 시작하지 않는다")
    void rejectsPartiallyConfiguredCredentials(String partialCredentials) {
        contextRunner.withPropertyValues(partialCredentials).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("빈 리전은 거부한다")
    void rejectsBlankRegion() {
        contextRunner.withPropertyValues("aws.ses.region=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }
}
