package withoutjjin.withoutjjin.global.config;


import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import withoutjjin.withoutjjin.domain.auth.controller.handler.ApiAuthenticationEntryPoint;
import withoutjjin.withoutjjin.domain.auth.controller.handler.OAuth2LoginFailureHandler;
import withoutjjin.withoutjjin.domain.auth.oauth.CustomOidcUserService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/invitations").permitAll()
                        .requestMatchers(
                                "/",
                                "/index.html",
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
                        .defaultSuccessUrl("/", true) // 로그인 후 테스트 화면으로 돌아온다.
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService)
                                //Google 사용자 정보를 처리할 때 우리가 만든 클래스를 사용하도록 연결.
                        )
                        .failureHandler(oAuth2LoginFailureHandler)// 구글 로그인 실패시
                ).logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true) //서버 세션 무효화
                        .clearAuthentication(true) // 인증 정보 정리
                        .deleteCookies("SESSION") //현재 기본 이름인 세션 쿠키 삭제
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT); // 로그아웃 성공 후 204 반환
                        })
                );

        return http.build();
    }
}
