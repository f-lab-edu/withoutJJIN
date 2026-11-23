package withoutjjin.withoutjjin.domain.document;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionTransactions;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionTransactions.PreparedUpload;
import withoutjjin.withoutjjin.domain.document.storage.S3AsyncUploadVerifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 작업 스레드 가용성 확인용 작업의 공간을 확보한다. 업무 요청 한도는 max-in-flight=2로 따로 검증한다.
@SpringBootTest(properties = {"document.upload-completion.async.threads=1", "document.upload-completion.async.queue-capacity=4",
        "document.upload-completion.async.response-timeout=2s", "document.upload-completion.async.max-in-flight=2"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(DocumentUploadCompletionAsyncApiTest.OtherAsyncController.class)
class DocumentUploadCompletionAsyncApiTest {
    private static final String PATH = "/api/v1/uploads/{uploadId}/complete";
    private static final String ISSUER = "https://accounts.google.com";
    @Autowired private MockMvc mvc;
    @Autowired private OtherAsyncController other;
    @Autowired @Qualifier("uploadCompletionTaskExecutor") private Executor executor;
    @MockitoBean private DocumentUploadCompletionTransactions transactions;
    @MockitoBean private S3AsyncUploadVerifier verifier;

    @Test
    void returnsWorkerDuringS3WaitAndKeepsAccountsSeparateWhileRejectingOverload() throws Exception {
        var firstHead = stubUpload(123L, "member-a");
        var secondHead = stubUpload(124L, "member-b");
        MvcResult first;
        MvcResult second;
        try {
            first = perform(123, "member-a");
            drainExecutor();
            assertThat(first.getRequest().getAsyncContext().getTimeout()).isEqualTo(7000);
            assertThat(first.getResponse().isCommitted()).isFalse();
            second = perform(124, "member-b");
            drainExecutor();
            verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
            mvc.perform(post(PATH, 125).with(oidcLogin().idToken(token -> token.claim("iss", ISSUER).subject("member-c")))
                            .with(csrf().asHeader()))
                    .andExpect(request().asyncNotStarted()).andExpect(status().isServiceUnavailable());
            verify(transactions, never()).prepare(125L, ISSUER, "member-c");
            firstHead.complete(null);
            drainExecutor();
            secondHead.complete(null);
        } finally {
            firstHead.complete(null);
            drainExecutor();
            secondHead.complete(null);
            drainExecutor();
        }
        first.getAsyncResult(5000);
        second.getAsyncResult(5000);
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").value(123)).andExpect(jsonPath("$.documentVersionId").value(789));
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk()).andExpect(jsonPath("$.uploadId").value(124));
        verify(transactions).prepare(123L, ISSUER, "member-a");
        verify(transactions).prepare(124L, ISSUER, "member-b");
    }

    @Test
    void futureDeadlineReturns504AndLateS3DoesNotCommit() throws Exception {
        var head = stubUpload(123L, "member-a");
        try {
            MvcResult result = perform(123, "member-a");
            drainExecutor();
            result.getAsyncResult(5000);
            mvc.perform(asyncDispatch(result)).andExpect(status().isGatewayTimeout());
            head.complete(null);
            drainExecutor();
            verify(transactions, never()).complete(anyLong(), anyString(), anyString(), any());
        } finally { head.complete(null); drainExecutor(); }
    }

    @Test
    void servletFallbackTimeoutAlsoReturns504() throws Exception {
        var head = stubUpload(123L, "member-a");
        try {
            MvcResult result = perform(123, "member-a");
            drainExecutor();
            MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
            for (AsyncListener listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
            result.getAsyncResult(5000);
            mvc.perform(asyncDispatch(result)).andExpect(status().isGatewayTimeout());
        } finally { head.complete(null); drainExecutor(); }
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

    private CompletableFuture<Void> stubUpload(long id, String subject) {
        var prepared = new PreparedUpload(id + 1, "original-" + id, 1024L, "docx", null, null);
        when(transactions.prepare(id, ISSUER, subject)).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-");
            return prepared;
        });
        var head = new CompletableFuture<Void>();
        when(verifier.verify(prepared.objectKey(), 1024L, "docx")).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-");
            return head;
        });
        when(transactions.complete(id, ISSUER, subject, prepared)).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("upload-completion-");
            return new CompleteUploadResponse(id, 456L, 789L);
        });
        return head;
    }

    private void drainExecutor() throws Exception {
        CompletableFuture.runAsync(() -> {}, executor).get(5, TimeUnit.SECONDS);
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
