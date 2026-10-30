package withoutjjin.withoutjjin.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailScheduler;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailWorker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class InvitationEmailSchedulingConfigTest {

    @Test
    @DisplayName("발송설정을 지정하지 않으면 자동발송을 시작하지 않는다")
    void doesNotStartAutomaticSendingWithoutConfiguration() {
        InvitationEmailWorker worker = mock(InvitationEmailWorker.class);

        contextRunner(worker).run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(InvitationEmailScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(worker);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", ""})
    @DisplayName("발송설정이 true가 아니면 자동발송을 시작하지 않는다")
    void doesNotStartAutomaticSendingUnlessEnabled(String enabled) {
        InvitationEmailWorker worker = mock(InvitationEmailWorker.class);

        contextRunner(worker).withPropertyValues("invitation.mail.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(InvitationEmailScheduler.class);
                    verifyNoInteractions(worker);
                });
    }

    @Test
    @DisplayName("활성화하면 주기적으로 실행하고 첫 실행이 실패해도 다시 실행한다")
    void runsPeriodicallyAndContinuesAfterFirstExecutionFails() {
        InvitationEmailWorker worker = mock(InvitationEmailWorker.class);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch secondAttempt = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("test batch query failure");
            }
            secondAttempt.countDown();
            return null;
        }).when(worker).processPendingBatch();

        contextRunner(worker).withPropertyValues(
                "invitation.mail.enabled=true",
                "invitation.mail.poll-delay=50ms"
        ).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InvitationEmailScheduler.class);
            assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        });
    }

    private ApplicationContextRunner contextRunner(InvitationEmailWorker worker) {
        // Spring의 실제 스케줄러를 사용하되, DB와 SES에 접근하는 작업자는 모의 객체로 대체한다.
        return new ApplicationContextRunner()
                .withUserConfiguration(InvitationEmailSchedulingConfig.class)
                .withBean(InvitationEmailWorker.class, () -> worker);
    }
}
