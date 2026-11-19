package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class S3ObjectDeleterTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final S3ObjectDeleter deleter = new S3ObjectDeleter(s3Client, "ftfm-test-bucket");

    @ParameterizedTest
    @ValueSource(strings = {
            "workspaces/10/uploads/original.docx",
            "workspaces/10/review-pdfs/converted.pdf"
    })
    @DisplayName("설정된 버킷에서 전달받은 원본 또는 PDF 저장 키 하나만 삭제 요청한다")
    void deletesOnlyRequestedObject(String objectKey) {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        deleter.delete(objectKey);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(captor.getValue().key()).isEqualTo(objectKey);
        verifyNoMoreInteractions(s3Client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("S3 버킷 이름이 없으면 S3ObjectDeleter 생성을 거부한다")
    void rejectsMissingBucket(String bucket) {
        assertThatThrownBy(() -> new S3ObjectDeleter(s3Client, bucket))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("버킷");

        verifyNoInteractions(s3Client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("삭제할 저장 키가 없으면 S3에 요청하지 않는다")
    void rejectsMissingObjectKey(String objectKey) {
        assertThatThrownBy(() -> deleter.delete(objectKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("저장 키");

        verifyNoInteractions(s3Client);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 500})
    @DisplayName("S3 접근 거부와 오류 응답을 삭제 성공으로 처리하지 않는다")
    void propagatesS3Failure(int statusCode) {
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(statusCode).message("S3 삭제 실패").build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> deleter.delete("workspaces/10/uploads/original.docx"))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("S3 통신 오류를 삭제 성공으로 처리하지 않는다")
    void propagatesConnectionFailure() {
        SdkClientException failure = SdkClientException.create("S3 연결 실패");
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> deleter.delete("workspaces/10/uploads/original.docx"))
                .isSameAs(failure);
    }
}
