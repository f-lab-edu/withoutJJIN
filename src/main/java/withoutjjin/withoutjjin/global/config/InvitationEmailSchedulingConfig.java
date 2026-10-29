package withoutjjin.withoutjjin.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailScheduler;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationEmailWorker;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "invitation.mail", name = "enabled", havingValue = "true")
public class InvitationEmailSchedulingConfig {

    @Bean
    public InvitationEmailScheduler invitationEmailScheduler(InvitationEmailWorker worker) {
        return new InvitationEmailScheduler(worker);
    }
}
