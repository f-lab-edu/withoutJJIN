package withoutjjin.withoutjjin.global.config;

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
    void 발송설정을_지정하지_않으면_자동발송을_시작하지_않는다() {
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
    void 발송설정이_true가_아니면_자동발송을_시작하지_않는다(String enabled) {
        InvitationEmailWorker worker = mock(InvitationEmailWorker.class);

        contextRunner(worker).withPropertyValues("invitation.mail.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(InvitationEmailScheduler.class);
                    verifyNoInteractions(worker);
                });
    }

    @Test
    void 활성화하면_주기적으로_실행하고_첫_실행이_실패해도_다시_실행한다() {
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
