package withoutjjin.withoutjjin.global.config;

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
    void 설정에서_인증정보와_리전을_읽어_SES_클라이언트를_생성한다() {
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
    void 키를_직접_설정하지_않으면_기본_인증정보_탐색을_사용한다() {
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
    void 키_두개_중_하나만_설정하면_시작하지_않는다(String partialCredentials) {
        contextRunner.withPropertyValues(partialCredentials).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void 빈_리전은_거부한다() {
        contextRunner.withPropertyValues("aws.ses.region=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }
}
