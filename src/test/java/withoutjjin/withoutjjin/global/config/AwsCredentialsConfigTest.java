package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import static org.assertj.core.api.Assertions.*;

class AwsCredentialsConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AwsCredentialsConfig.class);

    @Test void explicitCredentialsRemainAvailableForS3() {
        runner.withPropertyValues("aws.credentials.access-key-id=test-id", "aws.credentials.secret-access-key=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AwsCredentialsProvider.class);
                    var credentials = context.getBean(AwsCredentialsProvider.class).resolveCredentials();
                    assertThat(credentials.accessKeyId()).isEqualTo("test-id");
                    assertThat(credentials.secretAccessKey()).isEqualTo("test-secret");
                });
    }

    @Test void defaultProviderRemainsAvailableForIamRole() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AwsCredentialsProvider.class)).isInstanceOf(DefaultCredentialsProvider.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws.credentials.access-key-id=test-id", "aws.credentials.secret-access-key=test-secret"})
    void rejectsPartiallyConfiguredCredentials(String partial) {
        runner.withPropertyValues(partial).run(context -> assertThat(context).hasFailed());
    }
}
