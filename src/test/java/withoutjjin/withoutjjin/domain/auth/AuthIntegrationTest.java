package withoutjjin.withoutjjin.domain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository repository;

    @Test
    void 로그인하지_않으면_내_정보_조회는_401이다() throws Exception { // 인증없이 접근
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }


    @Test
    void 로그인한_회원의_정보를_조회한다() throws Exception {
        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        repository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", issuer) //JWT에들어가는 한 항목 칭하는것 = claim
                                .subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email")
                        .value("auth-test@example.com"))
                .andExpect(jsonPath("$.displayName")
                        .value("로그인테스트회원"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void CSRF_토큰_없이_로그아웃하면_403이다() throws Exception { // 세션으로 사용자를 판별할수없어서 CSRF토큰으로 판별
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(oidcLogin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 유효한_CSRF_토큰으로_로그아웃하면_204이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(oidcLogin())
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }
}


// MockMvc는 테스트용 요청 객체를 만들어
// 보안 필터와 Spring MVC를 거쳐 실제 요청 처리 코드를 실행한다.
// 네트워크로 HTTP 요청을 보내지는 않는다.

// 앞서 작성한 실패 처리 테스트는 요청·응답 객체를 직접 넘겨
// 실패 처리 메서드만 검사했다.
// 이번 테스트는 Spring의 요청 처리 흐름까지 함께 검사한다.