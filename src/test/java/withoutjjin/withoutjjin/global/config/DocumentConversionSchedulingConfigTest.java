package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionScheduler;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailWorker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentConversionSchedulingConfigTest {

    @Test
    @DisplayName("활성화 속성을 지정하지 않은 컨텍스트에서는 PDF 스케줄러를 등록하지 않는다")
    void doesNotRegisterSchedulerWithoutEnabledProperty() {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);

        runner(processor).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DocumentConversionScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(processor);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", ""})
    @DisplayName("비활성화하면 PDF 스케줄러와 전용 실행 스레드를 등록하지 않는다")
    void doesNotRegisterSchedulerUnlessEnabled(String enabled) {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);

        runner(processor).withPropertyValues("document.conversion.worker.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(DocumentConversionScheduler.class)
                    .doesNotHaveBean("documentConversionTaskScheduler");
            verifyNoInteractions(processor);
        });
    }

    @Test
    @DisplayName("활성화하면 전용 단일 스레드를 등록하고 컨텍스트 종료 시 정리한다")
    void registersSingleThreadSchedulerAndShutsItDown() {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);
        AtomicReference<ThreadPoolTaskScheduler> scheduler = new AtomicReference<>();

        runner(processor).withPropertyValues("document.conversion.worker.enabled=true",
                "document.conversion.worker.poll-delay=1h").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DocumentConversionScheduler.class);
            ThreadPoolTaskScheduler bean = context.getBean("documentConversionTaskScheduler", ThreadPoolTaskScheduler.class);
            scheduler.set(bean);
            assertThat(bean.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            verifyNoInteractions(processor);
        });

        assertThat(scheduler.get().getScheduledThreadPoolExecutor().isShutdown()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "invalid", ""})
    @DisplayName("조회 간격이 유효하지 않으면 시작을 거부한다")
    void rejectsInvalidPollDelay(String delay) {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);

        runner(processor).withPropertyValues("document.conversion.worker.enabled=true",
                "document.conversion.worker.poll-delay=" + delay).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("조회 간격");
            verifyNoInteractions(processor);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("조회 건수가 양수가 아니면 시작을 거부한다")
    void rejectsInvalidBatchSize(int batchSize) {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);

        runner(processor).withPropertyValues("document.conversion.worker.enabled=true",
                "document.conversion.worker.poll-delay=1h", "document.conversion.worker.batch-size=" + batchSize)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("조회 건수");
                    verifyNoInteractions(processor);
                });
    }

    @Test
    @Timeout(10)
    @DisplayName("전용 스레드에서 주기적으로 실행하고 첫 조회 실패 후에도 다시 실행한다")
    void runsPeriodicallyAfterFirstQueryFailure() {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch secondAttempt = new CountDownLatch(1);
        when(processor.processPendingBatch(2)).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("테스트용 조회 실패");
            }
            secondAttempt.countDown();
            return 0;
        });

        runner(processor).withPropertyValues("document.conversion.worker.enabled=true",
                "document.conversion.worker.poll-delay=20ms", "document.conversion.worker.batch-size=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(threadName.get()).startsWith("document-conversion-");
                    assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
                });
    }

    @Test
    @Timeout(10)
    @DisplayName("한 회차가 끝난 뒤 설정한 대기시간이 지나야 다음 회차를 실행한다")
    void waitsFixedDelayAfterBatchCompletes() {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong firstFinishedAt = new AtomicLong();
        AtomicLong secondStartedAt = new AtomicLong();
        when(processor.processPendingBatch(5)).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                firstStarted.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                firstFinishedAt.set(System.nanoTime());
            } else {
                secondStartedAt.compareAndSet(0, System.nanoTime());
                secondStarted.countDown();
            }
            return 0;
        });

        runner(processor).withPropertyValues("document.conversion.worker.enabled=true",
                "document.conversion.worker.poll-delay=100ms").run(context -> {
            try {
                assertThat(context).hasNotFailed();
                assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(secondStarted.await(250, TimeUnit.MILLISECONDS)).isFalse();
                releaseFirst.countDown();
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(secondStartedAt.get() - firstFinishedAt.get()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(90));
            } finally {
                releaseFirst.countDown();
            }
        });
    }

    @Test
    @Timeout(10)
    @DisplayName("PDF 변환이 진행 중이어도 초대 메일 스케줄러는 별도 스레드에서 실행한다")
    void keepsInvitationSchedulerIndependentFromPdfConversion() {
        DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);
        InvitationEmailWorker mailWorker = mock(InvitationEmailWorker.class);
        CountDownLatch pdfStarted = new CountDownLatch(1);
        CountDownLatch releasePdf = new CountDownLatch(1);
        CountDownLatch mailDuringPdf = new CountDownLatch(1);
        AtomicBoolean pdfBlocked = new AtomicBoolean();
        AtomicReference<String> mailThread = new AtomicReference<>();
        when(processor.processPendingBatch(5)).thenAnswer(invocation -> {
            pdfBlocked.set(true);
            pdfStarted.countDown();
            assertThat(releasePdf.await(5, TimeUnit.SECONDS)).isTrue();
            pdfBlocked.set(false);
            return 0;
        });
        doAnswer(invocation -> {
            if (pdfBlocked.get()) {
                mailThread.set(Thread.currentThread().getName());
                mailDuringPdf.countDown();
            }
            return null;
        }).when(mailWorker).processPendingBatch();

        runner(processor).withUserConfiguration(InvitationEmailSchedulingConfig.class)
                .withBean(InvitationEmailWorker.class, () -> mailWorker)
                .withPropertyValues("document.conversion.worker.enabled=true", "document.conversion.worker.poll-delay=20ms",
                        "invitation.mail.enabled=true", "invitation.mail.poll-delay=20ms")
                .run(context -> {
                    try {
                        assertThat(context).hasNotFailed();
                        assertThat(pdfStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(mailDuringPdf.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(mailThread.get()).startsWith("invitation-email-");
                        assertThat(context.getBean("documentConversionTaskScheduler"))
                                .isNotSameAs(context.getBean("invitationEmailTaskScheduler"));
                    } finally {
                        releasePdf.countDown();
                    }
                });
    }

    private ApplicationContextRunner runner(DocumentConversionBatchProcessor processor) {
        return new ApplicationContextRunner().withUserConfiguration(DocumentConversionSchedulingConfig.class)
                .withBean(DocumentConversionBatchProcessor.class, () -> processor);
    }
}
