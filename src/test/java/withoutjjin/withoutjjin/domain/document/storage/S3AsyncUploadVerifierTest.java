package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class S3AsyncUploadVerifierTest {

    private static final String TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final S3AsyncObjectReader reader = mock(S3AsyncObjectReader.class);
    private final S3AsyncUploadVerifier verifier = new S3AsyncUploadVerifier(reader);

    @Test
    void waitsForFutureCompletionWithoutWaitingOnTheCallingThread() throws Exception {
        CompletableFuture<HeadObjectResponse> head = new CompletableFuture<>();
        when(reader.head("key")).thenReturn(head);

        CompletableFuture<Void> result = verifier.verify("key", 1024L, TYPE);

        assertThat(result).isNotDone();
        head.complete(HeadObjectResponse.builder().contentLength(1024L).contentType(TYPE.toUpperCase()).build());
        assertThat(result.get()).isNull();
        verify(reader).head("key");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1023, 1025})
    void rejectsSizeMismatch(long size) {
        when(reader.head("key")).thenReturn(CompletableFuture.completedFuture(
                HeadObjectResponse.builder().contentLength(size).contentType(TYPE).build()));
        assertMismatch(verifier.verify("key", 1024L, TYPE), "StoreFile.sizeBytes");
    }

    @Test
    void rejectsMissingLength() {
        when(reader.head("key")).thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().contentType(TYPE).build()));
        assertMismatch(verifier.verify("key", 1024L, TYPE), "StoreFile.sizeBytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "text/plain", ""})
    void rejectsMediaTypeMismatch(String type) {
        when(reader.head("key")).thenReturn(CompletableFuture.completedFuture(
                HeadObjectResponse.builder().contentLength(1024L).contentType(type).build()));
        assertMismatch(verifier.verify("key", 1024L, TYPE), "StoreFile.mediaType");
    }

    @Test
    void rejectsMissingMediaType() {
        when(reader.head("key")).thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().contentLength(1024L).build()));
        assertMismatch(verifier.verify("key", 1024L, TYPE), "StoreFile.mediaType");
    }

    @Test
    void propagatesSdkFailureWithoutReplacingTheCause() {
        SdkClientException failure = SdkClientException.create("mock failure");
        when(reader.head("key")).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> verifier.verify("key", 1024L, TYPE).get()).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(exception.getCause()).isSameAs(failure));
    }

    private static void assertMismatch(CompletableFuture<?> result, String message) {
        assertThatThrownBy(result::get).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class).hasMessageContaining(message));
    }
}
