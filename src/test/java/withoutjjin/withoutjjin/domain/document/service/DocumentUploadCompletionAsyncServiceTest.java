package withoutjjin.withoutjjin.domain.document.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.global.config.UploadCompletionAsyncProperties;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Timeout(10)
class DocumentUploadCompletionAsyncServiceTest {

    private final DocumentUploadCompletionService delegate = mock(DocumentUploadCompletionService.class);

    @Test
    void invokesExistingServiceOnDedicatedThread() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        AtomicReference<String> thread = new AtomicReference<>();
        CompleteUploadResponse expected = new CompleteUploadResponse(1L, 2L, 3L);
        when(delegate.completeUpload(1L, "issuer", "subject")).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            return expected;
        });
        try {
            assertThat(service(executor, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject").get(5, TimeUnit.SECONDS))
                    .isEqualTo(expected);
            assertThat(thread.get()).startsWith("upload-completion-test-");
        } finally { executor.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 409, 410, 502, 503})
    void preservesBusinessFailureWithoutDoubleWrapping(int status) {
        ResponseStatusException failure = new ResponseStatusException(HttpStatusCode.valueOf(status), "업무 오류");
        when(delegate.completeUpload(1L, "issuer", "subject")).thenThrow(failure);

        CompletableFuture<CompleteUploadResponse> result = service(Runnable::run, Duration.ofSeconds(5))
                .completeUpload(1L, "issuer", "subject");

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(exception.getCause()).isSameAs(failure));
    }

    @Test
    void rejectsOverloadBeforeInvokingBusinessService() {
        Executor rejected = task -> { throw new RejectedExecutionException("full"); };
        assertThatThrownBy(() -> service(rejected, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("504 응답 이후에도 이미 실행 중인 업무는 계속될 수 있다")
    void responseTimeoutDoesNotClaimToCancelRunningWork() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(delegate.completeUpload(1L, "issuer", "subject")).thenAnswer(invocation -> {
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return new CompleteUploadResponse(1L, 2L, 3L);
            } finally { finished.countDown(); }
        });
        try {
            CompletableFuture<CompleteUploadResponse> result = service(executor, Duration.ofMillis(500))
                    .completeUpload(1L, "issuer", "subject");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertGatewayTimeout(result);
            assertThat(finished.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            verify(delegate).completeUpload(1L, "issuer", "subject");
        } finally { release.countDown(); executor.shutdown(); }
    }

    @Test
    @DisplayName("대기열에서 응답 제한이 지난 작업은 나중에 업무 처리를 시작하지 않는다")
    void skipsExpiredQueuedWork() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try {
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
            assertGatewayTimeout(service(executor, Duration.ofMillis(500)).completeUpload(1L, "issuer", "subject"));
            release.countDown();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            verifyNoInteractions(delegate);
        } finally { release.countDown(); executor.shutdown(); }
    }

    private DocumentUploadCompletionAsyncService service(Executor executor, Duration timeout) {
        return new DocumentUploadCompletionAsyncService(delegate, executor, new UploadCompletionAsyncProperties(1, 2, timeout));
    }

    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("upload-completion-test-");
        executor.initialize();
        return executor;
    }

    private static void assertGatewayTimeout(CompletableFuture<?> result) {
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(exception.getCause()).isInstanceOfSatisfying(ResponseStatusException.class,
                        response -> assertThat(response.getStatusCode().value()).isEqualTo(504)));
    }
}
