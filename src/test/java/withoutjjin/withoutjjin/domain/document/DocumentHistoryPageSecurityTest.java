package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentHistoryPageSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private S3DownloadUrlGenerator generator;

    @ParameterizedTest
    @ValueSource(strings = {"/workspaces/3/documents/10/history", "/workspaces/3/documents/10/versions/91/review"})
    void redirectsAnonymousPageRequestsToLogin(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workspaces/3/documents/10/history", "/workspaces/3/documents/10/versions/91/review"})
    void servesOnlyTheStaticLayoutAfterLogin(String path) throws Exception {
        mvc.perform(get(path).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
