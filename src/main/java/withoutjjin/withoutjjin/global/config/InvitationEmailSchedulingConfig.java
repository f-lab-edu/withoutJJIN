package withoutjjin.withoutjjin.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailScheduler;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailWorker;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "invitation.mail", name = "enabled", havingValue = "true")
public class InvitationEmailSchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler invitationEmailTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("invitation-email-");
        return scheduler;
    }

    @Bean
    public InvitationEmailScheduler invitationEmailScheduler(InvitationEmailWorker worker) {
        return new InvitationEmailScheduler(worker);
    }
}
