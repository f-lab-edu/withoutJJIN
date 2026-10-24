package withoutjjin.withoutjjin.global.config;


import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import withoutjjin.withoutjjin.domain.auth.controller.handler.ApiAuthenticationEntryPoint;
import withoutjjin.withoutjjin.domain.auth.oauth.CustomOidcUserService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;


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
                .exceptionHandling(exceptions -> exceptions//인증·접근 거부와 관련된 예외를 어떻게 처리할지 설정
                .defaultAuthenticationEntryPointFor(
                        apiAuthenticationEntryPoint, // 인증이 필요할 때 실행할 처리 객체
                        PathPatternRequestMatcher
                                .withDefaults()
                                .matcher("/api/**") // 요청 경로가 조건에 맞는지 검사하는 객체
                )
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