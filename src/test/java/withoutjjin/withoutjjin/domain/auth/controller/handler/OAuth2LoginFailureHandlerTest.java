package withoutjjin.withoutjjin.domain.auth.controller.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginFailureHandlerTest {

    private final OAuth2LoginFailureHandler handler =
            new OAuth2LoginFailureHandler(new HttpSessionRequestCache());

    // MockHttpServletRequest 테스트용 HTTP 요청 객체
    // MockHttpServletResponse 상태·헤더·본문을 기록해서 확인할 수 있는 테스트용 응답 객체
    // OAuth2Error              오류 코드를 담는 객체
    // handleFailure(...)       오류를 만들어 핸들러를 호출하는 공통 테스트 도우미

    // @ValueSource             반복 실행에 사용할 입력값 목록

    // MOCK : 모의 객체라는 뜻  쓰는이유 =>
    // 매번 Google 로그인 화면에 들어가 취소하거나 실패 상황을 만들어야 하잖아.
    // 특히 state 오류 같은 조건은 재현하기 번거로워.
    //  => 그럼 같은 환경이 아닌데 테스트하는 의미가 있나? ????
    // “이 테스트가 어디까지 증명하는가”를 제한해서 봐야 함
    // Location : HTTP 응답 헤더의 이름으로, 리다이렉트 응답에서는 브라우저가 다음으로 이동할 주소


    @Test
    @DisplayName("접근을 거부하면 취소안내로 이동한다")
    void redirectsAccessDeniedToCancellationNotice() throws IOException {
        MockHttpServletResponse response = handleFailure("access_denied");
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location"))
                .isEqualTo("/login?error=LOGIN_CANCELED");
    }


    @ParameterizedTest   // 입력값을 바꿔 같은 테스트를 반복 실행
    @ValueSource(strings = { // 지정한 입력값 // 현재 2번 발생하는 메소드
            "authorization_request_not_found",
            "invalid_state_parameter"
    })
    @DisplayName("로그인시도 연결에 실패하면 400을 반환한다")
    void returns400WhenLoginAttemptCannotBeMatched(
            String errorCode
    ) throws IOException {
        MockHttpServletResponse response = handleFailure(errorCode);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType())
                .startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\": \"OAUTH_STATE_INVALID\"");
        assertThat(response.getHeader("Location")).isNull();
    }


    @Test
    @DisplayName("그외 인증실패는 일반실패안내로 이동한다")
    void redirectsOtherAuthenticationFailuresToFailureNotice() throws IOException {
        MockHttpServletResponse response = handleFailure("invalid_token");

        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location"))
                .isEqualTo("/login?error=GOOGLE_AUTH_FAILED");
    }






    private MockHttpServletResponse handleFailure(
            String errorCode
    ) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthenticationException exception =
                new OAuth2AuthenticationException(
                        new OAuth2Error(errorCode)
                );

        handler.onAuthenticationFailure(request, response, exception);

        return response;
    }
}
