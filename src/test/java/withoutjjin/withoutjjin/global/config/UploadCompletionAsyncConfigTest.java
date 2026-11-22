package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadCompletionAsyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UploadCompletionAsyncConfig.class);

    @Test
    @DisplayName("기본 실행기 크기와 응답 제한을 적용하고 컨텍스트 종료 시 실행기를 닫는다")
    void createsBoundedExecutorAndShutsItDown() {
        AtomicReference<ThreadPoolTaskExecutor> captured = new AtomicReference<>();
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean("uploadCompletionTaskExecutor", ThreadPoolTaskExecutor.class);
            captured.set(executor);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getQueueCapacity()).isEqualTo(8);
            assertThat(context.getBean(UploadCompletionAsyncProperties.class).responseTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
        assertThat(captured.get().getThreadPoolExecutor().isShutdown()).isTrue();
    }

    @Test
    @Timeout(10)
    @DisplayName("전용 스레드·유한 대기열을 사용하며 포화 요청을 호출자 스레드에서 실행하지 않는다")
    void rejectsOverloadWithoutCallerRuns() {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        runner.withPropertyValues("document.upload-completion.async.threads=1", "document.upload-completion.async.queue-capacity=1")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean("uploadCompletionTaskExecutor", ThreadPoolTaskExecutor.class);
                    try {
                        executor.execute(() -> {
                            threadName.set(Thread.currentThread().getName());
                            running.countDown();
                            try { release.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                        });
                        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                        executor.execute(queuedDone::countDown);
                        assertThatThrownBy(() -> executor.execute(() -> { throw new AssertionError("포화 요청을 실행하면 안 됩니다."); }))
                                .isInstanceOf(RejectedExecutionException.class);
                        assertThat(threadName.get()).startsWith("upload-completion-");
                    } finally {
                        release.countDown();
                    }
                    assertThat(queuedDone.await(5, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Test
    void doesNotReplaceAnUnqualifiedExecutor() {
        Executor general = Runnable::run;
        runner.withBean("generalExecutor", Executor.class, () -> general)
                .withBean(ExecutorHolder.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ExecutorHolder.class).executor()).isSameAs(general);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"document.upload-completion.async.threads=0", "document.upload-completion.async.threads=-1",
            "document.upload-completion.async.queue-capacity=-1", "document.upload-completion.async.response-timeout=0ms",
            "document.upload-completion.async.response-timeout=-1s", "document.upload-completion.async.response-timeout=1ns",
            "document.upload-completion.async.response-timeout=invalid"})
    void rejectsInvalidSettings(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    record ExecutorHolder(Executor executor) { }
}
