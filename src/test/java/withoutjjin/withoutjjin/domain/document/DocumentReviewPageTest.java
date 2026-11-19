package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import withoutjjin.withoutjjin.domain.document.controller.DocumentReviewPageController;
import withoutjjin.withoutjjin.global.config.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentReviewPageTest {

    @ParameterizedTest
    @ValueSource(strings = {"/workspaces/3/documents/10/review", "/workspaces/3/documents/10/versions/91/review"})
    void servesReviewLayoutWithoutCachingOrEmbeddingPrivateDocumentData(String url) throws Exception {
        MockMvcBuilders.standaloneSetup(new DocumentReviewPageController()).build()
                .perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("id=\"pdf-pages\"")))
                .andExpect(content().string(containsString("id=\"feedback-heading\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workspaces/3/documents/10/review", "/workspaces/3/documents/10/versions/91/review"})
    void remembersTheReviewPageForReturningAfterLogin(String url) {
        RequestCache cache = new SecurityConfig(null, null).loginRequestCache();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", url);
        request.setServletPath(url);
        MockHttpServletResponse response = new MockHttpServletResponse();
        cache.saveRequest(request, response);
        assertThat(cache.getRequest(request, response).getRedirectUrl())
                .startsWith("http://localhost" + url);
    }
}
