package withoutjjin.withoutjjin.domain.document;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"document.upload-completion.async.threads=1", "document.upload-completion.async.queue-capacity=1",
        "document.upload-completion.async.response-timeout=2s"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(DocumentUploadCompletionAsyncApiTest.OtherAsyncController.class)
class DocumentUploadCompletionAsyncApiTest {

    private static final String PATH = "/api/v1/uploads/{uploadId}/complete";
    private static final String ISSUER = "https://accounts.google.com";
    @Autowired private MockMvc mvc;
    @Autowired private OtherAsyncController other;
    @MockitoBean private DocumentUploadCompletionService completionService;

    @Test
    void releasesRequestThreadAndKeepsQueuedAccountsSeparateWhileRejectingOverload() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        when(completionService.completeUpload(123L, ISSUER, "member-a")).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new CompleteUploadResponse(123L, 456L, 789L);
        });
        when(completionService.completeUpload(124L, ISSUER, "member-b")).thenReturn(new CompleteUploadResponse(124L, 457L, 790L));
        MvcResult first;
        MvcResult second;
        try {
            first = perform(123, "member-a");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("upload-completion-");
            assertThat(first.getRequest().getAsyncContext().getTimeout()).isEqualTo(7000);
            assertThat(first.getResponse().isCommitted()).isFalse();
            second = perform(124, "member-b");
            mvc.perform(post(PATH, 125).with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject("member-c")))
                            .with(csrf().asHeader()))
                    .andExpect(request().asyncNotStarted()).andExpect(status().isServiceUnavailable());
            verify(completionService, never()).completeUpload(125L, ISSUER, "member-c");
        } finally { release.countDown(); }
        first.getAsyncResult(5000);
        second.getAsyncResult(5000);
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").value(123));
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk()).andExpect(jsonPath("$.uploadId").value(124));
        verify(completionService).completeUpload(123L, ISSUER, "member-a");
        verify(completionService).completeUpload(124L, ISSUER, "member-b");
    }

    @Test
    void futureDeadlineReturns504() throws Exception {
        blockedRequest(false);
    }

    @Test
    void servletFallbackTimeoutAlsoReturns504() throws Exception {
        blockedRequest(true);
    }

    private void blockedRequest(boolean servletTimeout) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(completionService.completeUpload(123L, ISSUER, "member-a")).thenAnswer(invocation -> {
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return new CompleteUploadResponse(123L, 456L, 789L);
            } finally { finished.countDown(); }
        });
        try {
            MvcResult result = perform(123, "member-a");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            if (servletTimeout) {
                MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
                for (AsyncListener listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
            }
            result.getAsyncResult(5000);
            mvc.perform(asyncDispatch(result)).andExpect(status().isGatewayTimeout());
            assertThat(finished.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void doesNotChangeAnotherControllersAsyncTimeout() throws Exception {
        MvcResult result = mvc.perform(get("/test/async-unrelated").with(oidcLogin()))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getRequest().getAsyncContext().getTimeout()).isNotEqualTo(7000);
        other.result.complete("ok");
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    private MvcResult perform(long id, String subject) throws Exception {
        return mvc.perform(post(PATH, id).with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject(subject)))
                .with(csrf().asHeader())).andExpect(request().asyncStarted()).andReturn();
    }

    @RestController
    static class OtherAsyncController {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        @GetMapping("/test/async-unrelated")
        CompletableFuture<String> other() { return result; }
    }
}
