package withoutjjin.withoutjjin.domain.document.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionTransactions.PreparedUpload;
import withoutjjin.withoutjjin.domain.document.storage.S3AsyncUploadVerifier;
import withoutjjin.withoutjjin.global.config.UploadCompletionAsyncProperties;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class DocumentUploadCompletionAsyncServiceTest {
    private static final PreparedUpload PREPARED = new PreparedUpload(10L, "original.docx", 1024L, "docx", null, null);
    private static final CompleteUploadResponse COMPLETED = new CompleteUploadResponse(1L, 2L, 3L);
    private final DocumentUploadCompletionTransactions transactions = mock(DocumentUploadCompletionTransactions.class);
    private final S3AsyncUploadVerifier verifier = mock(S3AsyncUploadVerifier.class);

    @Test
    void returnsWorkerWhileS3IsPendingAndResumesJpaOnDedicatedExecutor() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        CompletableFuture<Void> head = pending();
        when(transactions.prepare(1L, "issuer", "subject")).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-test-");
            return PREPARED;
        });
        when(verifier.verify("original.docx", 1024L, "docx")).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-test-");
            return head;
        });
        when(transactions.complete(1L, "issuer", "subject", PREPARED)).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-test-");
            return COMPLETED;
        });
        try {
            var result = service(executor, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject");
            // 같은 단일 스레드가 다른 작업을 실행하면 S3를 기다리며 점유되지 않았다는 뜻이다.
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(result).isNotDone();
            verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
            Thread sdk = new Thread(() -> head.complete(null), "sdk-io-test");
            sdk.start();
            sdk.join(5000);
            assertThat(sdk.isAlive()).isFalse();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(COMPLETED);
        } finally { head.complete(null); executor.shutdown(); }
    }

    @Test
    void completedUploadSkipsS3AndSecondTransaction() throws Exception {
        when(transactions.prepare(1L, "issuer", "subject"))
                .thenReturn(new PreparedUpload(null, null, null, null, null, COMPLETED));
        assertThat(service(Runnable::run, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject").get())
                .isEqualTo(COMPLETED);
        verifyNoInteractions(verifier);
        verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 409, 410})
    void preservesPrepareFailureAndReturnsAdmission(int status) throws Exception {
        var failure = new ResponseStatusException(HttpStatusCode.valueOf(status), "업무 오류");
        when(transactions.prepare(1L, "issuer", "subject")).thenThrow(failure)
                .thenReturn(new PreparedUpload(null, null, null, null, null, COMPLETED));
        var service = service(Runnable::run, Duration.ofSeconds(5));
        assertThatThrownBy(() -> service.completeUpload(1L, "issuer", "subject").get())
                .isInstanceOfSatisfying(ExecutionException.class, error -> assertThat(error.getCause()).isSameAs(failure));
        assertThat(service.completeUpload(1L, "issuer", "subject").get()).isEqualTo(COMPLETED);
        verifyNoInteractions(verifier);
    }

    @ParameterizedTest
    @CsvSource({"404,404", "403,502", "500,502"})
    void mapsWrappedS3FailureAndReturnsAdmission(int s3Status, int httpStatus) throws Exception {
        CompletableFuture<Void> head = pending();
        var service = service(Runnable::run, Duration.ofSeconds(5));
        var first = service.completeUpload(1L, "issuer", "subject");
        head.completeExceptionally(new CompletionException(S3Exception.builder().statusCode(s3Status).message("failed").build()));
        assertStatus(first, httpStatus);
        assertCanComplete(service);
    }

    @Test
    void mapsConnectionFailureTo503() {
        CompletableFuture<Void> head = pending();
        var first = service(Runnable::run, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject");
        head.completeExceptionally(SdkClientException.create("connection failed"));
        assertStatus(first, 503);
    }

    @Test
    void mapsMetadataMismatchTo409() {
        CompletableFuture<Void> head = pending();
        var first = service(Runnable::run, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject");
        head.completeExceptionally(new IllegalStateException("StoreFile.sizeBytes"));
        assertStatus(first, 409);
        verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void mapsSynchronousSdkFailureBeforeFutureIsReturned() {
        pending();
        when(verifier.verify("original.docx", 1024L, "docx")).thenThrow(SdkClientException.create("signing failed"));
        assertStatus(service(Runnable::run, Duration.ofSeconds(5)).completeUpload(1L, "issuer", "subject"), 503);
    }

    @Test
    void preservesCompletionFailureAndReturnsAdmission() throws Exception {
        pending().complete(null);
        var failure = new ResponseStatusException(HttpStatusCode.valueOf(410), "expired");
        when(transactions.complete(1L, "issuer", "subject", PREPARED)).thenThrow(failure);
        var service = service(Runnable::run, Duration.ofSeconds(5));
        assertStatus(service.completeUpload(1L, "issuer", "subject"), 410);
        assertCanComplete(service);
    }

    @Test
    void rejectsInFlightOverloadWhileWorkerIsFree() throws Exception {
        CompletableFuture<Void> head = pending();
        var service = service(Runnable::run, Duration.ofSeconds(5));
        var first = service.completeUpload(1L, "issuer", "subject");
        assertOverloaded(service);
        verify(transactions, times(1)).prepare(anyLong(), anyString(), anyString());
        head.complete(null);
        assertThat(first.get()).isEqualTo(COMPLETED);
        assertCanComplete(service);
    }

    @Test
    void timeoutKeepsAdmissionUntilHeadEndsAndSkipsLateCommit() throws Exception {
        CompletableFuture<Void> head = pending();
        var service = service(Runnable::run, Duration.ofMillis(100));
        assertStatus(service.completeUpload(1L, "issuer", "subject"), 504);
        assertOverloaded(service);
        head.complete(null);
        verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
        assertCanComplete(service);
    }

    @Test
    void cancellationKeepsAdmissionUntilHeadEndsAndSkipsLateCommit() throws Exception {
        CompletableFuture<Void> head = pending();
        var service = service(Runnable::run, Duration.ofSeconds(5));
        assertThat(service.completeUpload(1L, "issuer", "subject").cancel(false)).isTrue();
        assertOverloaded(service);
        head.complete(null);
        verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
        assertCanComplete(service);
    }

    @Test
    void initialExecutorRejectionReturnsAdmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Executor rejectOnce = task -> {
            if (calls.getAndIncrement() == 0) throw new RejectedExecutionException("full");
            task.run();
        };
        var service = service(rejectOnce, Duration.ofSeconds(5));
        assertOverloaded(service);
        verifyNoInteractions(transactions, verifier);
        assertCanComplete(service);
    }

    @Test
    void completionExecutorRejectionReturns503AndAdmission() throws Exception {
        CompletableFuture<Void> head = pending();
        AtomicInteger calls = new AtomicInteger();
        Executor rejectCompletion = task -> {
            if (calls.incrementAndGet() == 2) throw new RejectedExecutionException("full");
            task.run();
        };
        var service = service(rejectCompletion, Duration.ofSeconds(5));
        var first = service.completeUpload(1L, "issuer", "subject");
        head.complete(null);
        assertStatus(first, 503);
        verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
        assertCanComplete(service);
    }

    @Test
    void queuedExpiredPrepareDoesNotAccessDatabase() throws Exception {
        Queue<Runnable> queue = new ArrayDeque<>();
        var service = service(queue::add, Duration.ofMillis(100));
        assertStatus(service.completeUpload(1L, "issuer", "subject"), 504);
        assertOverloaded(service);
        queue.remove().run();
        verifyNoInteractions(transactions, verifier);
        when(transactions.prepare(1L, "issuer", "subject"))
                .thenReturn(new PreparedUpload(null, null, null, null, null, COMPLETED));
        var next = service.completeUpload(1L, "issuer", "subject");
        queue.remove().run();
        assertThat(next.get()).isEqualTo(COMPLETED);
    }

    @Test
    void timeoutDuringRunningCommitKeepsAdmissionUntilTransactionEnds() throws Exception {
        ThreadPoolTaskExecutor executor = executor();
        pending().complete(null);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(transactions.complete(1L, "issuer", "subject", PREPARED)).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return COMPLETED;
        });
        try {
            var service = service(executor, Duration.ofMillis(500));
            var first = service.completeUpload(1L, "issuer", "subject");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertStatus(first, 504);
            assertOverloaded(service);
            release.countDown();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertCanComplete(service);
        } finally { release.countDown(); executor.shutdown(); }
    }

    private CompletableFuture<Void> pending() {
        var head = new CompletableFuture<Void>();
        when(transactions.prepare(1L, "issuer", "subject")).thenReturn(PREPARED);
        when(verifier.verify("original.docx", 1024L, "docx")).thenReturn(head);
        when(transactions.complete(1L, "issuer", "subject", PREPARED)).thenReturn(COMPLETED);
        return head;
    }

    private void assertCanComplete(DocumentUploadCompletionAsyncService service) throws Exception {
        when(transactions.prepare(1L, "issuer", "subject"))
                .thenReturn(new PreparedUpload(null, null, null, null, null, COMPLETED));
        assertThat(service.completeUpload(1L, "issuer", "subject").get(5, TimeUnit.SECONDS)).isEqualTo(COMPLETED);
    }

    private static void assertOverloaded(DocumentUploadCompletionAsyncService service) {
        assertThatThrownBy(() -> service.completeUpload(1L, "issuer", "subject"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    private DocumentUploadCompletionAsyncService service(Executor executor, Duration timeout) {
        return new DocumentUploadCompletionAsyncService(transactions, verifier, executor,
                new UploadCompletionAsyncProperties(1, 2, timeout, 1));
    }

    private static ThreadPoolTaskExecutor executor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("upload-completion-test-");
        executor.initialize();
        return executor;
    }

    private static void assertStatus(CompletableFuture<?> result, int status) {
        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                error -> assertThat(error.getCause()).isInstanceOfSatisfying(ResponseStatusException.class,
                        response -> assertThat(response.getStatusCode().value()).isEqualTo(status)));
    }
}
