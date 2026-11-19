package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import withoutjjin.withoutjjin.domain.document.controller.DocumentHistoryPageController;
import withoutjjin.withoutjjin.global.config.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentHistoryPageTest {
    @Test
    void servesHistoryLayoutWithoutEmbeddingPrivateDocumentData() throws Exception {
        MockMvcBuilders.standaloneSetup(new DocumentHistoryPageController()).build()
                .perform(get("/workspaces/3/documents/10/history"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("id=\"version-list\"")));
    }

    @Test
    void remembersHistoryPageForReturningAfterLogin() {
        RequestCache cache = new SecurityConfig(null, null).loginRequestCache();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspaces/3/documents/10/history");
        request.setServletPath("/workspaces/3/documents/10/history");
        MockHttpServletResponse response = new MockHttpServletResponse();
        cache.saveRequest(request, response);
        assertThat(cache.getRequest(request, response).getRedirectUrl())
                .startsWith("http://localhost/workspaces/3/documents/10/history");
    }
}
