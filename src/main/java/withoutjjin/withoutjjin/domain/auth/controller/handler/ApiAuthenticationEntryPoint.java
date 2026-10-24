package withoutjjin.withoutjjin.domain.auth.controller.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;



@Component
public class ApiAuthenticationEntryPoint
        implements AuthenticationEntryPoint {


    @Override
    public void commence(
            HttpServletRequest request, //인증이 필요할 때 Spring Security가 호출
            HttpServletResponse response,
            AuthenticationException authException
    )
            throws IOException {


        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); //HTTP 상태를 401로 설정
        response.setContentType("application/json");  //응답이 JSON임을 표시
        response.setCharacterEncoding(StandardCharsets.UTF_8.name()); //한글이 포함된 응답을 UTF-8로 작성
        response.setHeader("Cache-Control", "no-store"); //응답 본문 작성

        // API 요청에서 인증이 필요하면 우리가 만든 JSON 응답을 사용하도록 지정한 거 => 제이슨 응답
        response.getWriter().write("""
                {
                  "isSuccess": false,
                  "code": "AUTH_REQUIRED",
                  "message": "로그인이 필요합니다.",
                  "result": null
                }
                """);
        }



}
