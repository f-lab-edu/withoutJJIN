package withoutjjin.withoutjjin.global.config;


import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import withoutjjin.withoutjjin.domain.auth.oauth.CustomOidcUserService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/error",
                                "/login/**",
                                "/oauth2/**"
                        ).permitAll() //permitAll(): 로그인 전에도 접근 허용.
                        .anyRequest().authenticated() //나머지 요청은 로그인 필요.
                )
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService)
                                //Google 사용자 정보를 처리할 때 우리가 만든 클래스를 사용하도록 연결.
                        )
                );

        return http.build();
    }
}