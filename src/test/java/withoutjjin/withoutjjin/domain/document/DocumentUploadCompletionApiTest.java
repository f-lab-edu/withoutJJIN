package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Controller·Security·응답 형식을 검증한다. 실제 Service의 커밋은 별도 통합 테스트에서 검증한다.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentUploadCompletionApiTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String SUBJECT = "completion-api-user";
    private static final String PATH = "/api/v1/uploads/{uploadId}/complete";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DocumentUploadCompletionService completionService;

    @Test
    @DisplayName("로그인 정보와 uploadId를 Service에 전달하고 ID들을 JSON으로 응답한다")
    void completesUploadWithAuthenticatedAccountAndCsrf() throws Exception {
        when(completionService.completeUpload(123L, ISSUER, SUBJECT))
                .thenReturn(new CompleteUploadResponse(123L, 456L, 789L));

        mockMvc.perform(post(PATH, 123)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT)))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").value(123))
                .andExpect(jsonPath("$.documentId").value(456))
                .andExpect(jsonPath("$.documentVersionId").value(789));

        verify(completionService).completeUpload(123L, ISSUER, SUBJECT);
    }

    @Test
    @DisplayName("비로그인 완료 요청은 401 응답하고 Service를 호출하지 않는다")
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post(PATH, 123).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));

        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 완료 요청은 403 응답하고 Service를 호출하지 않는다")
    void rejectsMissingCsrfToken() throws Exception {
        mockMvc.perform(post(PATH, 123)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("CSRF 토큰이 잘못된 완료 요청은 403 응답하고 Service를 호출하지 않는다")
    void rejectsInvalidCsrfToken() throws Exception {
        mockMvc.perform(post(PATH, 123)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT)))
                        .with(csrf().asHeader().useInvalidToken()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(completionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    @DisplayName("잘못된 uploadId는 400 응답하고 Service를 호출하지 않는다")
    void rejectsInvalidUploadId(String uploadId) throws Exception {
        mockMvc.perform(post(PATH, uploadId)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT)))
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(completionService);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 409, 410, 502, 503})
    @DisplayName("Service의 실패 상태를 로그인 화면으로 리다이렉트하지 않고 HTTP 상태로 응답한다")
    void returnsServiceFailureStatus(int statusCode) throws Exception {
        when(completionService.completeUpload(123L, ISSUER, SUBJECT))
                .thenThrow(new ResponseStatusException(
                        HttpStatusCode.valueOf(statusCode), "테스트용 완료 처리 실패"));

        mockMvc.perform(post(PATH, 123)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT)))
                        .with(csrf().asHeader()))
                .andExpect(status().is(statusCode))
                .andExpect(header().doesNotExist("Location"));

        verify(completionService).completeUpload(123L, ISSUER, SUBJECT);
    }

    @Test
    @DisplayName("GET 요청은 완료 처리를 실행하지 않는다")
    void rejectsGetWithoutCompletingUpload() throws Exception {
        mockMvc.perform(get(PATH, 123)
                        .with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(SUBJECT))))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(completionService);
    }
}
