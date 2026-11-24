package withoutjjin.withoutjjin.domain.document.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.ProxyConfiguration;
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class S3AsyncObjectReaderHttpTest {

    @Test
    @Timeout(25)
    @DisplayName("실제 비동기 SDK의 HEAD 응답 대기 중에도 동일한 호출 스레드로 다른 작업을 실행한다")
    void realSdkReleasesCallingThreadWhileLocalHeadResponseIsDelayed() throws Exception {
        String type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverThreads = Executors.newSingleThreadExecutor();
        ExecutorService callingThread = Executors.newSingleThreadExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            received.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test response was not released");
                exchange.getResponseHeaders().set("Content-Length", "1024");
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try (S3AsyncClient client = S3AsyncClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local-key", "local-secret")))
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .forcePathStyle(true)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(1).maxPendingConnectionAcquires(1)
                        .eventLoopGroupBuilder(SdkEventLoopGroup.builder().numberOfThreads(1))
                        .proxyConfiguration(ProxyConfiguration.builder().useSystemPropertyValues(false)
                                .useEnvironmentVariableValues(false).build()))
                .build()) {
            S3AsyncObjectReader reader = new S3AsyncObjectReader(client, "async-head-test-bucket",
                    Duration.ofSeconds(10), Duration.ofSeconds(8));
            CompletableFuture<HeadObjectResponse> head = callingThread.submit(
                    () -> reader.head("workspaces/10/uploads/source.docx")).get(3, TimeUnit.SECONDS);
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(head).isNotDone();
            assertThat(callingThread.submit(() -> "calling thread is available").get(1, TimeUnit.SECONDS))
                    .isEqualTo("calling thread is available");
            assertThat(head).isNotDone();
            release.countDown();
            HeadObjectResponse response = head.get(5, TimeUnit.SECONDS);
            assertThat(response.contentLength()).isEqualTo(1024L);
            assertThat(response.contentType()).isEqualTo(type);
            assertThat(method.get()).isEqualTo("HEAD");
            assertThat(path.get()).isEqualTo("/async-head-test-bucket/workspaces/10/uploads/source.docx");
        } finally {
            release.countDown();
            server.stop(0);
            callingThread.shutdownNow();
            serverThreads.shutdownNow();
            assertThat(callingThread.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(serverThreads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
