package withoutjjin.withoutjjin.domain.workspace;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class workspaceCreationInterationTest {




    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    @DisplayName("로그인한 회원이 워크스페이스를 생성한다")
    void createsWorkspaceForAuthenticatedUser() throws Exception {
        // 1. 테스트 회원 저장

        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        AppUser user = appUserRepository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));
        // 2. 해당 회원의 로그인 정보를 넣어 생성 요청

        mockMvc.perform(post("/api/v1/workspaces")
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", issuer)
                                .subject(subject)))
                            .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "name": "A팀"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("A팀"));

        // 3. 응답이 201이고 팀 이름이 맞는지 확인
    }
    @Test
    @DisplayName("빈 이름으로 워크스페이스를 생성할 수 없다")
    void rejectsWorkspaceWithEmptyName()  throws Exception {
        // 1. 테스트 회원 저장

        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        AppUser user = appUserRepository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));
        // 2. 해당 회원의 로그인 정보를 넣어 생성 요청

        mockMvc.perform(post("/api/v1/workspaces")
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", issuer)
                                .subject(subject)))
                            .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "name": ""
                        }
                        """))
                .andExpect(status().isBadRequest());

        // 3. 응답이 201이고 팀 이름이 맞는지 확인
    }

    @ParameterizedTest
    @MethodSource("invalidWorkspaceNames")
    @DisplayName("올바르지 않은 이름은 거부한다")
    void rejectsInvalidWorkspaceName(String name) throws Exception {


        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        AppUser user = appUserRepository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));
        // 2. 해당 회원의 로그인 정보를 넣어 생성 요청

        mockMvc.perform(post("/api/v1/workspaces")
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", issuer)
                                .subject(subject)))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest());


    }


    static Stream<String> invalidWorkspaceNames() {
        return Stream.of(
                "",              // 빈 이름
                "   ",           // 공백만 있는 이름
                "가".repeat(81)   // 최대 80자 초과
        );
    }
}
