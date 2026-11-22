package withoutjjin.withoutjjin.domain.document.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.global.config.UploadCompletionAsyncProperties;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DocumentUploadCompletionAsyncService {

    private final DocumentUploadCompletionService completionService;
    private final Executor executor;
    private final UploadCompletionAsyncProperties properties;

    public DocumentUploadCompletionAsyncService(DocumentUploadCompletionService completionService,
                                               @Qualifier("uploadCompletionTaskExecutor") Executor executor,
                                               UploadCompletionAsyncProperties properties) {
        this.completionService = completionService;
        this.executor = executor;
        this.properties = properties;
    }

    public CompletableFuture<CompleteUploadResponse> completeUpload(Long uploadId, String issuer, String subject) {
        try {
            return CompletableFuture.supplyAsync(
                            () -> completionService.completeUpload(uploadId, issuer, subject), executor)
                    .orTimeout(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(failure -> {
                        if (failure instanceof TimeoutException) {
                            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                                    "업로드 완료 응답 대기시간을 초과했습니다. 업로드 상태를 확인한 뒤 다시 시도해 주세요.", failure);
                        }
                        // CompletionException을 중첩하지 않아 MVC가 기존 업무 예외를 그대로 처리한다.
                        if (failure instanceof CompletionException completion) throw completion;
                        throw new CompletionException(failure);
                    });
        } catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "업로드 완료 요청이 많습니다. 잠시 후 다시 시도해 주세요.", exception);
        }
    }
}
