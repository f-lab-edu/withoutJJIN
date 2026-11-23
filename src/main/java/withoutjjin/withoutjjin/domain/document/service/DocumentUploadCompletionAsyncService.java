package withoutjjin.withoutjjin.domain.document.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.storage.S3AsyncUploadVerifier;
import withoutjjin.withoutjjin.global.config.UploadCompletionAsyncProperties;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DocumentUploadCompletionAsyncService {

    private final DocumentUploadCompletionTransactions transactions;
    private final S3AsyncUploadVerifier s3UploadVerifier;
    private final Executor executor;
    private final UploadCompletionAsyncProperties properties;
    private final Semaphore inFlight;

    public DocumentUploadCompletionAsyncService(
            DocumentUploadCompletionTransactions transactions,
            S3AsyncUploadVerifier s3UploadVerifier,
            @Qualifier("uploadCompletionTaskExecutor") Executor executor,
            UploadCompletionAsyncProperties properties) {
        this.transactions = transactions;
        this.s3UploadVerifier = s3UploadVerifier;
        this.executor = executor;
        this.properties = properties;
        this.inFlight = new Semaphore(properties.maxInFlight());

    }

    public CompletableFuture<CompleteUploadResponse> completeUpload(Long uploadId, String issuer, String subject) {
        if (!inFlight.tryAcquire()) throw overloaded(null);

        CompletableFuture<CompleteUploadResponse> response = new CompletableFuture<>();
        CompletableFuture<CompleteUploadResponse> result = response.handle((value, failure) -> {
            if (failure == null) return value;
            Throwable cause = unwrap(failure);
            if (cause instanceof TimeoutException) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "업로드 완료 응답 대기시간을 초과했습니다. 업로드 상태를 확인한 뒤 다시 시도해 주세요.", cause);
            }
            if (cause instanceof RejectedExecutionException) throw overloaded(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new CompletionException(cause);
        });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) response.cancel(false);
        });
        response.orTimeout(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS);

        CompletableFuture<CompleteUploadResponse> work;
        try {
            // S3 요청 시작도 전용 실행기에서 수행한다. SDK의 인증정보 조회 등이 요청 스레드로 돌아가지 않는다.
            work = CompletableFuture.supplyAsync(
                            () -> prepareAndVerify(uploadId, issuer, subject, response), executor)
                    .thenCompose(next -> next);
        } catch (RuntimeException failure) {
            inFlight.release();
            response.completeExceptionally(failure);
            if (failure instanceof RejectedExecutionException) throw overloaded(failure);
            throw failure;
        }
        // HTTP 응답 제한과 실제 업무의 종료는 다르다. 진행 중인 S3·DB 작업이 끝나야 한도를 반환한다.
        work.whenComplete((value, failure) -> {
            inFlight.release();
            if (failure == null) response.complete(value);
            else response.completeExceptionally(unwrap(failure));
        });
        return result;
    }

    private CompletableFuture<CompleteUploadResponse> prepareAndVerify(
            Long uploadId, String issuer, String subject, CompletableFuture<?> response) {
        requireOpen(response);
        DocumentUploadCompletionTransactions.PreparedUpload prepared = transactions.prepare(uploadId, issuer, subject);
        requireOpen(response);
        if (prepared.completedResponse() != null) {
            return CompletableFuture.completedFuture(prepared.completedResponse());
        }

        CompletableFuture<Void> verification;
        try {
            verification = s3UploadVerifier.verify(prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(verificationFailure(failure));
        }
        return verification.handle((unused, failure) -> {
            if (failure != null) throw new CompletionException(verificationFailure(unwrap(failure)));
            return unused;
        }).thenCompose(unused -> {
            requireOpen(response);
            // S3 Future를 완료한 SDK 스레드에서 JPA를 실행하지 않는다.
            return CompletableFuture.supplyAsync(() -> {
                requireOpen(response);
                return transactions.complete(uploadId, issuer, subject, prepared);
            }, executor);
        });
    }

    private static void requireOpen(CompletableFuture<?> response) {
        if (response.isDone()) throw new CancellationException("업로드 완료 응답이 종료되었습니다.");
    }

    private static Throwable verificationFailure(Throwable failure) {
        if (failure instanceof S3Exception s3) {
            return new ResponseStatusException(s3.statusCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
                    "S3 업로드 파일을 확인할 수 없습니다.", s3);
        }
        if (failure instanceof SdkClientException || failure instanceof CancellationException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "S3 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.", failure);
        }
        if (failure instanceof IllegalStateException) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "S3 파일 정보가 StoreFile과 일치하지 않습니다.", failure);
        }
        return failure;
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static ResponseStatusException overloaded(Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "업로드 완료 요청이 많습니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
