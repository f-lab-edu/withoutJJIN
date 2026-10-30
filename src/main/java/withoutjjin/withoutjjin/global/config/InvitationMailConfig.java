package withoutjjin.withoutjjin.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class InvitationMailConfig {

    @Bean
    public Clock invitationMailClock() {
        // 기존 초대 만료 시각과 같은 시간대를 사용한다.
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

}
