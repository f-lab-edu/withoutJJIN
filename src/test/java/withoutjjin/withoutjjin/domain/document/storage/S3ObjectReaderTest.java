package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ObjectReaderTest {

    @Test
    @DisplayName("설정된 버킷과 전달받은 저장 키로 HeadObject를 호출한다")
    void readsObjectFromConfiguredBucketAndKey() {
        // 실제 AWS 호출을 하지 않는 S3Client 모의 객체
        S3Client s3Client = mock(S3Client.class);

        S3ObjectReader reader =
                new S3ObjectReader(s3Client, "ftfm-test-bucket");

        String objectKey = "workspaces/10/uploads/test-file";

        HeadObjectResponse expectedResponse =
                HeadObjectResponse.builder()
                        .contentLength(1024L)
                        .contentType(
                                "application/vnd.openxmlformats-officedocument"
                                        + ".wordprocessingml.document"
                        )
                        .build();

        // S3Client.headObject()가 호출되면 준비한 응답을 반환
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(expectedResponse);

        HeadObjectResponse result = reader.head(objectKey);

        // S3Client.headObject()에 전달된 HeadObjectRequest를 확보
        ArgumentCaptor<HeadObjectRequest> captor =
                ArgumentCaptor.forClass(HeadObjectRequest.class);

        verify(s3Client).headObject(captor.capture());

        HeadObjectRequest actualRequest = captor.getValue();

        assertThat(actualRequest.bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(actualRequest.key()).isEqualTo(objectKey);
        assertThat(result).isSameAs(expectedResponse);
    }



    @ParameterizedTest
    @ValueSource(ints = {404, 403, 500})
    @DisplayName("S3가 오류를 반환하면 S3ObjectReader가 예외를 전달한다")
    void propagatesS3Error(int statusCode) {
        S3Client s3Client = mock(S3Client.class);
        S3ObjectReader reader =
                new S3ObjectReader(s3Client, "ftfm-test-bucket");

        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("S3 조회 실패")
                .build();

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(failure);

        assertThatThrownBy(() ->
                reader.head("workspaces/10/uploads/test-file")
        ).isSameAs(failure);
    }

    @Test
    @DisplayName("통신 오류가 발생하면 S3ObjectReader가 예외를 전달한다")
    void propagatesConnectionFailure() {
        S3Client s3Client = mock(S3Client.class);
        S3ObjectReader reader =
                new S3ObjectReader(s3Client, "ftfm-test-bucket");

        SdkClientException failure = SdkClientException.builder()
                .message("S3 연결 실패")
                .build();

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(failure);

        assertThatThrownBy(() ->
                reader.head("workspaces/10/uploads/test-file")
        ).isSameAs(failure);
    }







}