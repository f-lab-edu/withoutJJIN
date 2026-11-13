package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import withoutjjin.withoutjjin.global.config.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentReviewPageTest {

    @Test
    void servesReviewLayoutWithoutCachingOrEmbeddingPrivateDocumentData() throws Exception {
        MockMvcBuilders.standaloneSetup(new DocumentReviewPageController()).build()
                .perform(get("/workspaces/3/documents/10/review"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("id=\"pdf-pages\"")))
                .andExpect(content().string(containsString("id=\"feedback-heading\"")));
    }

    @Test
    void remembersTheReviewPageForReturningAfterLogin() {
        RequestCache cache = new SecurityConfig(null, null).loginRequestCache();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspaces/3/documents/10/review");
        request.setServletPath("/workspaces/3/documents/10/review");
        MockHttpServletResponse response = new MockHttpServletResponse();
        cache.saveRequest(request, response);
        assertThat(cache.getRequest(request, response).getRedirectUrl())
                .startsWith("http://localhost/workspaces/3/documents/10/review");
    }
}
