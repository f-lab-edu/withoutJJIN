package withoutjjin.withoutjjin.domain.auth.controller.handler;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2LoginFailureHandler   implements AuthenticationFailureHandler {


    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        response.setHeader("Cache-Control", "no-store");

        String errorCode = "";

        if (exception instanceof OAuth2AuthenticationException oauthException) {
            errorCode = oauthException.getError().getErrorCode();
        }

        // 로그인 시도 기록이 없거나 state가 일치하지 않는 경우
        if ("authorization_request_not_found".equals(errorCode) //브라우저에서 시작한 로그인 시도와 연결할 수 없다
                || "invalid_state_parameter".equals(errorCode))  // 원인을 단정하기보다 연결이 검증되지 않은 응답으로 로그인시키지 않는 것
        {

            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            response.getWriter().write("""
                    {
                      "isSuccess": false,
                      "code": "OAUTH_STATE_INVALID",
                      "message": "로그인 요청을 확인할 수 없습니다. 다시 시도해 주세요.",
                      "result": null
                    }
                    """);

            return;
        }

        String reason = "access_denied".equals(errorCode)
                ? "LOGIN_CANCELED"
                : "GOOGLE_AUTH_FAILED";

        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/login?error=" + reason);
    }
}
