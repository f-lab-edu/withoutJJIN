package withoutjjin.withoutjjin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailScheduler;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WithoutJjinApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("테스트 공통 설정을 적용하고 자동 메일 발송 스케줄러를 시작하지 않는다")
    void contextLoads() {
        assertThat(environment.getActiveProfiles()).contains("test");
        assertThat(environment.getProperty("invitation.mail.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("invitation.mail.allowed-recipient")).isEmpty();
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(applicationContext.getBeansOfType(InvitationEmailScheduler.class)).isEmpty();
    }





}
