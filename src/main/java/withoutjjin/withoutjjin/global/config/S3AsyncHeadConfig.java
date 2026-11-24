package withoutjjin.withoutjjin.global.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3AsyncHeadProperties.class)
public class S3AsyncHeadConfig {

    @Bean(destroyMethod = "close")
    public S3AsyncClient s3HeadAsyncClient(
            @Qualifier("awsCredentialsProvider") AwsCredentialsProvider credentialsProvider,
            @Value("${aws.s3.region:ap-northeast-2}") String region,
            S3AsyncHeadProperties properties
    ) {
        if (region.isBlank()) {
            throw new IllegalArgumentException("S3 비동기 조회 리전이 필요합니다.");
        }
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                // Builder를 전달하여 S3AsyncClient.close()가 HTTP 클라이언트와 I/O 스레드도 정리하게 한다.
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(properties.maxConcurrency())
                        .maxPendingConnectionAcquires(properties.maxPendingConnectionAcquires())
                        .connectionTimeout(properties.connectionTimeout())
                        .connectionAcquisitionTimeout(properties.connectionAcquisitionTimeout())
                        .eventLoopGroupBuilder(SdkEventLoopGroup.builder().numberOfThreads(properties.eventLoopThreads())))
                .build();
    }
}
