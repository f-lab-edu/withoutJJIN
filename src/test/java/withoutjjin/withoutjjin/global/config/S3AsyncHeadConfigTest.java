package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

class S3AsyncHeadConfigTest {

    private final AwsCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("async-head-test-key", "async-head-test-secret"));
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3AsyncHeadConfig.class)
            .withBean("awsCredentialsProvider", AwsCredentialsProvider.class, () -> credentials);

    @Test
    void createsDedicatedAsyncClientWithExistingCredentialsAndDefaultLimits() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3AsyncClient.class);
            S3AsyncClient client = context.getBean("s3HeadAsyncClient", S3AsyncClient.class);
            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.AP_NORTHEAST_2);
            assertThat(client.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials);
            S3AsyncHeadProperties properties = context.getBean(S3AsyncHeadProperties.class);
            assertThat(properties.maxConcurrency()).isEqualTo(12);
            assertThat(properties.maxPendingConnectionAcquires()).isEqualTo(12);
            assertThat(properties.eventLoopThreads()).isEqualTo(2);
        });
    }

    @Test
    void usesConfiguredRegionAndLimits() {
        runner.withPropertyValues("aws.s3.region=us-east-1", "aws.s3.async-head.max-concurrency=3",
                "aws.s3.async-head.max-pending-connection-acquires=4", "aws.s3.async-head.event-loop-threads=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(S3AsyncClient.class).serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
                    assertThat(context.getBean(S3AsyncHeadProperties.class).maxConcurrency()).isEqualTo(3);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws.s3.region=", "aws.s3.async-head.max-concurrency=0",
            "aws.s3.async-head.max-pending-connection-acquires=0", "aws.s3.async-head.event-loop-threads=0",
            "aws.s3.async-head.connection-timeout=0ms", "aws.s3.async-head.connection-timeout=-1s",
            "aws.s3.async-head.connection-acquisition-timeout=1ns", "aws.s3.async-head.connection-acquisition-timeout=invalid"})
    void rejectsInvalidSettings(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
