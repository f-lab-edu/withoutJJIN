package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3AsyncObjectReaderTest {

    private final S3AsyncClient client = mock(S3AsyncClient.class);
    private final S3AsyncObjectReader reader = new S3AsyncObjectReader(client, "async-head-test-bucket",
            Duration.ofSeconds(10), Duration.ofSeconds(3));

    @Test
    void returnsTheSdkFutureBeforeItsResponseAndPreservesRequestTimeouts() throws Exception {
        CompletableFuture<HeadObjectResponse> sdkFuture = new CompletableFuture<>();
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(sdkFuture);

        CompletableFuture<HeadObjectResponse> result = reader.head("workspaces/10/uploads/source.docx");

        assertThat(result).isSameAs(sdkFuture).isNotDone();
        ArgumentCaptor<HeadObjectRequest> captured = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(captured.capture());
        assertThat(captured.getValue().bucket()).isEqualTo("async-head-test-bucket");
        assertThat(captured.getValue().key()).isEqualTo("workspaces/10/uploads/source.docx");
        assertThat(captured.getValue().overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
        assertThat(captured.getValue().overrideConfiguration().orElseThrow().apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
        HeadObjectResponse response = HeadObjectResponse.builder().contentLength(1024L).build();
        sdkFuture.complete(response);
        assertThat(result.get()).isSameAs(response);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 500})
    void preservesS3Failure(int status) {
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(status).message("mock failure").build();
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(CompletableFuture.failedFuture(failure));
        assertCause(reader.head("key"), failure);
    }

    @Test
    void preservesConnectionFailureAndSdkSynchronousFailure() {
        SdkClientException failure = SdkClientException.create("mock connection failure");
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(CompletableFuture.failedFuture(failure));
        assertCause(reader.head("key"), failure);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(failure);
        assertCause(reader.head("key"), failure);
    }

    @Test
    void preservesSdkCancellation() {
        CompletableFuture<HeadObjectResponse> sdkFuture = new CompletableFuture<>();
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(sdkFuture);
        CompletableFuture<HeadObjectResponse> result = reader.head("key");
        sdkFuture.cancel(false);
        assertThat(result).isCancelled();
    }

    private static void assertCause(CompletableFuture<?> result, Throwable failure) {
        assertThatThrownBy(result::get).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(exception.getCause()).isSameAs(failure));
    }
}
