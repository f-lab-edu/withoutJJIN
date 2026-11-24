package withoutjjin.withoutjjin.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class InvitationConfig {
    @Bean
    public Clock invitationClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
