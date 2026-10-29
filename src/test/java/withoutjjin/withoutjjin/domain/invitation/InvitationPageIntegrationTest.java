package withoutjjin.withoutjjin.domain.invitation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.MockMvc;
import withoutjjin.withoutjjin.domain.auth.controller.handler.OAuth2LoginFailureHandler;
import withoutjjin.withoutjjin.domain.auth.oauth.InvitationAuthorizationRequestResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "invitation.mail.enabled=false")
@AutoConfigureMockMvc
class InvitationPageIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestCache requestCache;
    @Autowired private SavedRequestAwareAuthenticationSuccessHandler successHandler;
    @Autowired private OAuth2LoginFailureHandler failureHandler;
    @Autowired private ClientRegistrationRepository registrations;

    @Test
    void 로그인_전에_초대화면을_열고_토큰은_HTML에_출력하지_않는다() throws Exception {
        mockMvc.perform(get("/invite").param("token", "page-test-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(containsString("초대 수락하기")))
                .andExpect(content().string(not(containsString("page-test-token"))));
        mockMvc.perform(get("/invite.html")).andExpect(status().isOk());
    }

    @Test
    void 초대에서_로그인하면_Google_로그인을_시작하고_가입은_수행하지_않는다() throws Exception {
        mockMvc.perform(get("/invite/continue"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", endsWith("/oauth2/authorization/google")));
        mockMvc.perform(get("/invite/continue").with(oidcLogin()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/invite"));
    }

    @Test
    void 초대에서_시작한_로그인은_성공후_고정된_초대경로로_돌아간다() throws Exception {
        MockHttpServletRequest request = invitationLoginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        requestCache.saveRequest(request, response);
        successHandler.onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).startsWith("http://localhost/invite/continue");
        assertThat(response.getRedirectedUrl()).doesNotContain("token=");
    }

    @Test
    void 일반_로그인은_홈으로_돌아간다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, null);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void API와_외부주소는_로그인_복귀요청으로_저장하지_않는다() {
        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/invitations", "/untrusted"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            requestCache.saveRequest(request, response);
            assertThat(requestCache.getRequest(request, response)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"access_denied", "invalid_state_parameter", "authorization_request_not_found", "invalid_token"})
    void 초대_로그인_취소나_실패는_초대화면으로_돌아가고_저장요청을_지운다(String code) throws Exception {
        MockHttpServletRequest request = invitationLoginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        requestCache.saveRequest(request, response);
        failureHandler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error(code)));
        String reason = switch (code) {
            case "access_denied" -> "LOGIN_CANCELED";
            case "invalid_state_parameter", "authorization_request_not_found" -> "OAUTH_STATE_INVALID";
            default -> "GOOGLE_AUTH_FAILED";
        };
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location")).isEqualTo("/invite?loginError=" + reason);
        assertThat(requestCache.getRequest(request, response)).isNull();
    }

    @Test
    void 초대_로그인만_계정선택을_요청하고_state_nonce_PKCE는_유지한다() {
        InvitationAuthorizationRequestResolver resolver = new InvitationAuthorizationRequestResolver(
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization"), requestCache);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");
        var normal = resolver.resolve(request, "google");
        assertThat(normal.getAdditionalParameters()).doesNotContainKey("prompt");

        MockHttpServletRequest start = invitationLoginRequest();
        requestCache.saveRequest(start, new MockHttpServletResponse());
        request.setSession(start.getSession());
        var invitation = resolver.resolve(request, "google");
        assertThat(invitation.getAdditionalParameters()).containsEntry("prompt", "select_account");
        assertThat(invitation.getState()).isNotBlank();
        assertThat(invitation.getAdditionalParameters()).containsKey("nonce");
        assertThat(invitation.getScopes()).contains("openid", "email", "profile");
        // 실제 등록에 PKCE가 적용돼 있으면, 복사 후에도 challenge와 verifier가 함께 유지된다.
        if (normal.getAdditionalParameters().containsKey("code_challenge")) {
            assertThat(invitation.getAdditionalParameters()).containsKey("code_challenge");
            assertThat(invitation.getAttributes()).containsKey("code_verifier");
        }
        assertThat(resolver.resolve(new MockHttpServletRequest("GET", "/unrelated"))).isNull();
    }

    private MockHttpServletRequest invitationLoginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/invite/continue");
        request.setServletPath("/invite/continue");
        return request;
    }
}
