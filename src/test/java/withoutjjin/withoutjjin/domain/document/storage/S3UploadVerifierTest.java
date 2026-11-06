package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class S3UploadVerifierTest {

    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document";

    private S3ObjectReader reader;
    private S3UploadVerifier verifier;
    private StoreFile storeFile;

    @BeforeEach
    void setUp() {
        reader = mock(S3ObjectReader.class); // 모의 객체로 선언하여서 실제로요청하지않음
        verifier = new S3UploadVerifier(reader);

        storeFile = new StoreFile(
                mock(Workspace.class),
                "workspaces/10/uploads/test-file",
                "기획서.docx",
                MEDIA_TYPE,
                1024L
        );
    }

    @Test
    @DisplayName("S3 파일 크기와 Content-Type이 StoreFile과 일치하면 통과한다")
    void acceptsMatchingMetadata() {
        when(reader.head(storeFile.getObjectKey())) // 요청
                .thenReturn(HeadObjectResponse.builder()// 응답반환
                        .contentLength(1024L)
                        .contentType(MEDIA_TYPE)
                        .build());

        assertThatCode(() -> verifier.verify(storeFile))
                .doesNotThrowAnyException();

        verify(reader).head(storeFile.getObjectKey());
    }

    @Test
    @DisplayName("S3 파일 크기가 StoreFile.sizeBytes와 다르면 거부한다")
    void rejectsSizeMismatch() {
        when(reader.head(storeFile.getObjectKey()))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(2048L)
                        .contentType(MEDIA_TYPE)
                        .build());

        assertThatThrownBy(() -> verifier.verify(storeFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StoreFile.sizeBytes");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/pdf", ""})
    @DisplayName("S3 Content-Type이 없거나 StoreFile.mediaType과 다르면 거부한다")
    void rejectsContentTypeMismatch(String contentType) {
        when(reader.head(storeFile.getObjectKey()))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(1024L)
                        .contentType(contentType)
                        .build());

        assertThatThrownBy(() -> verifier.verify(storeFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StoreFile.mediaType");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 403, 500})
    @DisplayName("S3 조회가 실패하면 S3UploadVerifier도 예외를 전달한다")
    void propagatesS3ReadFailure(int statusCode) {
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("S3 조회 실패")
                .build();

        when(reader.head(storeFile.getObjectKey()))
                .thenThrow(failure);

        assertThatThrownBy(() -> verifier.verify(storeFile))
                .isSameAs(failure);

        verify(reader).head(storeFile.getObjectKey());
    }

    @Test
    @DisplayName("통신 오류가 발생하면 S3UploadVerifier도 예외를 전달한다")
    void propagatesConnectionFailure() {
        SdkClientException failure = SdkClientException.builder()
                .message("S3 연결 실패")
                .build();

        when(reader.head(storeFile.getObjectKey()))
                .thenThrow(failure);

        assertThatThrownBy(() -> verifier.verify(storeFile))
                .isSameAs(failure);

        verify(reader).head(storeFile.getObjectKey());
    }
}