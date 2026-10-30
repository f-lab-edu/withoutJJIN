package withoutjjin.withoutjjin.global.config;


import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import withoutjjin.withoutjjin.domain.auth.controller.handler.ApiAuthenticationEntryPoint;
import withoutjjin.withoutjjin.domain.auth.controller.handler.OAuth2LoginFailureHandler;
import withoutjjin.withoutjjin.domain.auth.oauth.CustomOidcUserService;
import withoutjjin.withoutjjin.domain.auth.oauth.InvitationAuthorizationRequestResolver;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;

    @Bean
    public RequestCache loginRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        // 로그인 후 돌아갈 내부 화면만 저장한다. API 요청과 외부 returnUrl은 저장하지 않는다.
        cache.setRequestMatcher(new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/invite/continue"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/workspaces/{workspaceId}")
        ));
        return cache;
    }

    @Bean
    public SavedRequestAwareAuthenticationSuccessHandler loginSuccessHandler(RequestCache requestCache) {
        SavedRequestAwareAuthenticationSuccessHandler handler = new SavedRequestAwareAuthenticationSuccessHandler();
        handler.setRequestCache(requestCache);
        handler.setDefaultTargetUrl("/");
        return handler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, RequestCache requestCache,
            SavedRequestAwareAuthenticationSuccessHandler loginSuccessHandler,
            OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
            ClientRegistrationRepository clientRegistrations)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/invitations").permitAll()
                        .requestMatchers(HttpMethod.GET, "/invite", "/invite.html").permitAll()
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/error",
                                "/login/**",
                                "/oauth2/**"
                        ).permitAll() //permitAll(): 로그인 전에도 접근 허용.
                        .anyRequest().authenticated() //나머지 요청은 로그인 필요.
                )
                .requestCache(cache -> cache.requestCache(requestCache))
                .exceptionHandling(exceptions -> exceptions//인증·접근 거부와 관련된 예외를 어떻게 처리할지 설정
                .defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
                        new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/invite/continue"),
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/workspaces/{workspaceId}")
                        )
                )
                .defaultAuthenticationEntryPointFor(
                        apiAuthenticationEntryPoint, // 인증이 필요할 때 실행할 처리 객체
                        PathPatternRequestMatcher
                                .withDefaults()
                                .matcher("/api/**") // 요청 경로가 조건에 맞는지 검사하는 객체
                )
                )
                .oauth2Login(oauth -> oauth
                        .successHandler(loginSuccessHandler) // 초대에서 시작했으면 초대 화면으로 복귀한다.
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                                new InvitationAuthorizationRequestResolver(
                                        new DefaultOAuth2AuthorizationRequestResolver(
                                                clientRegistrations, "/oauth2/authorization"), requestCache)))
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
