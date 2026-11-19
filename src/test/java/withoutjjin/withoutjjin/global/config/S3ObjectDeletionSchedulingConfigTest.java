package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class S3ObjectDeletionSchedulingConfigTest {

    @Test
    void doesNotStartWithoutExplicitEnabledProperty() {
        S3ObjectDeletionBatchProcessor processor = mock(S3ObjectDeletionBatchProcessor.class);
        runner(processor).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(S3ObjectDeletionScheduler.class);
            verifyNoInteractions(processor);
        });
        runner(processor).withPropertyValues("document.storage.deletion.worker.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("s3ObjectDeletionTaskScheduler");
            verifyNoInteractions(processor);
        });
    }

    @Test
    void registersSeparateSingleThreadAndShutsItDown() {
        S3ObjectDeletionBatchProcessor processor = mock(S3ObjectDeletionBatchProcessor.class);
        AtomicReference<ThreadPoolTaskScheduler> scheduler = new AtomicReference<>();
        runner(processor).withPropertyValues("document.storage.deletion.worker.enabled=true",
                "document.storage.deletion.worker.poll-delay=1h").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(S3ObjectDeletionScheduler.class);
            ThreadPoolTaskScheduler bean = context.getBean("s3ObjectDeletionTaskScheduler", ThreadPoolTaskScheduler.class);
            scheduler.set(bean);
            assertThat(bean.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            verifyNoInteractions(processor);
        });
        assertThat(scheduler.get().getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "invalid", ""})
    void rejectsInvalidPollDelay(String delay) {
        S3ObjectDeletionBatchProcessor processor = mock(S3ObjectDeletionBatchProcessor.class);
        runner(processor).withPropertyValues("document.storage.deletion.worker.enabled=true",
                "document.storage.deletion.worker.poll-delay=" + delay).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("조회 간격");
            verifyNoInteractions(processor);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidBatchSize(int size) {
        S3ObjectDeletionBatchProcessor processor = mock(S3ObjectDeletionBatchProcessor.class);
        runner(processor).withPropertyValues("document.storage.deletion.worker.enabled=true",
                "document.storage.deletion.worker.poll-delay=1h", "document.storage.deletion.worker.batch-size=" + size)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("조회 건수");
                    verifyNoInteractions(processor);
                });
    }

    @Test
    @Timeout(10)
    void repeatsOnDedicatedThreadAfterQueryFailure(CapturedOutput output) {
        S3ObjectDeletionBatchProcessor processor = mock(S3ObjectDeletionBatchProcessor.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch second = new CountDownLatch(1);
        when(processor.processPendingBatch(2)).thenAnswer(call -> {
            threadName.set(Thread.currentThread().getName());
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("private-document.docx");
            }
            second.countDown();
            return 0;
        });
        runner(processor).withPropertyValues("document.storage.deletion.worker.enabled=true",
                "document.storage.deletion.worker.poll-delay=20ms", "document.storage.deletion.worker.batch-size=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(threadName.get()).startsWith("s3-object-deletion-");
                });
        assertThat(output.getAll()).contains("errorType=IllegalStateException")
                .doesNotContain("private-document.docx");
    }

    private ApplicationContextRunner runner(S3ObjectDeletionBatchProcessor processor) {
        return new ApplicationContextRunner().withUserConfiguration(S3ObjectDeletionSchedulingConfig.class)
                .withBean(S3ObjectDeletionBatchProcessor.class, () -> processor);
    }
}
