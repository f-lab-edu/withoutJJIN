package withoutjjin.withoutjjin.global.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConversionException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentConversionConfigTest {

    private final DocumentConversionConfig config = new DocumentConversionConfig();

    @ParameterizedTest
    @ValueSource(strings = {"ftp://converter.test", "/relative-path", "http:/missing-host"})
    @DisplayName("HTTP와 HTTPS가 아닌 변환 서버 주소를 거부한다")
    void rejectsInvalidBaseUrl(String address) {
        assertThatThrownBy(() -> config.wordPdfRestClient(URI.create(address), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP");
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    @DisplayName("0 또는 음수인 연결·응답 대기시간을 거부한다")
    void rejectsInfiniteOrNegativeTimeout(Duration connect, Duration read) {
        assertThatThrownBy(() -> config.wordPdfRestClient(URI.create("http://converter.test"), connect, read))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0보다");
    }

    static Stream<Arguments> invalidTimeouts() {
        return Stream.of(
                Arguments.of(Duration.ZERO, Duration.ofSeconds(1)),
                Arguments.of(Duration.ofSeconds(-1), Duration.ofSeconds(1)),
                Arguments.of(Duration.ofSeconds(1), Duration.ZERO),
                Arguments.of(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    @DisplayName("실제 HTTP 연결에서 응답 헤더와 본문을 기다리는 시간을 제한한다")
    void enforcesHttpResponseTimeout(boolean stallBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/forms/libreoffice/convert", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (stallBody) {
                    exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write("%PDF-1.7".getBytes());
                    exchange.getResponseBody().flush();
                }
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            WordPdfConverter converter = new WordPdfConverter(config.wordPdfRestClient(
                    address(server), Duration.ofSeconds(1), Duration.ofMillis(250)));
            byte[] docx = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();
            assertThatThrownBy(() -> converter.convert(docx))
                    .isInstanceOf(WordPdfConversionException.class)
                    .hasMessageContaining("대기시간")
                    .hasCauseInstanceOf(ResourceAccessException.class);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("리다이렉트 응답의 다른 주소로 DOCX를 전달하지 않는다")
    void doesNotFollowRedirects() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirectedCalls = new AtomicInteger();
        server.createContext("/forms/libreoffice/convert", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Location", address(server) + "/redirected");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        server.createContext("/redirected", exchange -> {
            redirectedCalls.incrementAndGet();
            try (exchange) {exchange.sendResponseHeaders(200, -1);}
        });
        server.start();
        try {
            WordPdfConverter converter = new WordPdfConverter(config.wordPdfRestClient(
                    address(server), Duration.ofSeconds(1), Duration.ofSeconds(2)));
            byte[] docx = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();
            assertThatThrownBy(() -> converter.convert(docx))
                    .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("307");
            assertThat(redirectedCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private static URI address(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
