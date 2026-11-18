package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3PdfWriterTest {

    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024;
    // 전송한 바이트가 그대로 유지되는지 확인하기 위한 데이터. PDF 구조 검증은 변환기에서 한다.
    private static final byte[] PDF_CONTENT = "%PDF-1.7\nPDF upload test".getBytes(StandardCharsets.US_ASCII);

    private final S3Client s3Client = mock(S3Client.class);
    private final DocumentStorageKeyGenerator keyGenerator = new DocumentStorageKeyGenerator();
    private final S3PdfWriter writer = new S3PdfWriter(s3Client, keyGenerator, "ftfm-test-bucket");

    @Test
    @DisplayName("PDF 데이터를 설정된 버킷에 업로드하고 저장 키를 반환한다")
    void uploadsPdfAndReturnsObjectKey() throws IOException {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("test-etag").build());

        String objectKey = writer.upload(10L, PDF_CONTENT);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(request.key()).isEqualTo(objectKey);
        assertThat(objectKey).startsWith("workspaces/10/review-pdfs/").endsWith(".pdf");
        assertThat(request.contentType()).isEqualTo("application/pdf");
        assertThat(request.ifNoneMatch()).isEqualTo("*");
        assertThat(bodyCaptor.getValue().optionalContentLength()).contains((long) PDF_CONTENT.length);
        try (InputStream body = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            assertThat(body.readAllBytes()).containsExactly(PDF_CONTENT);
        }
    }

    @Test
    @DisplayName("같은 워크스페이스의 PDF 업로드마다 새로운 저장 키를 사용한다")
    void usesNewKeyForEachUpload() {
        String first = writer.upload(10L, PDF_CONTENT);
        String second = writer.upload(10L, PDF_CONTENT);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));
        assertThat(first).isNotEqualTo(second);
        assertThat(captor.getAllValues()).extracting(PutObjectRequest::key).containsExactly(first, second);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("PDF 데이터가 없으면 S3에 요청하지 않는다")
    void rejectsMissingPdfContent(byte[] content) {
        assertThatThrownBy(() -> writer.upload(10L, content))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PDF");

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("64MiB를 넘는 PDF 데이터는 S3에 요청하지 않는다")
    void rejectsOversizedPdfContent() {
        byte[] content = new byte[MAX_PDF_BYTES + 1];

        assertThatThrownBy(() -> writer.upload(10L, content))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64MiB");

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("정확히 64MiB인 PDF 데이터는 업로드한다")
    void acceptsPdfAtSizeLimit() {
        byte[] content = new byte[MAX_PDF_BYTES];

        String objectKey = writer.upload(10L, content);

        ArgumentCaptor<RequestBody> captor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(any(PutObjectRequest.class), captor.capture());
        assertThat(objectKey).startsWith("workspaces/10/review-pdfs/");
        assertThat(captor.getValue().optionalContentLength()).contains((long) MAX_PDF_BYTES);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("워크스페이스 번호가 없거나 양수가 아니면 S3에 요청하지 않는다")
    void rejectsInvalidWorkspaceId(Long workspaceId) {
        assertThatThrownBy(() -> writer.upload(workspaceId, PDF_CONTENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("워크스페이스");

        verifyNoInteractions(s3Client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("S3 버킷 이름이 없으면 S3PdfWriter 생성을 거부한다")
    void rejectsMissingBucket(String bucket) {
        assertThatThrownBy(() -> new S3PdfWriter(s3Client, keyGenerator, bucket))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("버킷");

        verifyNoInteractions(s3Client);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 409, 412, 500})
    @DisplayName("접근 거부·조건부 저장 충돌·S3 서버 오류를 성공으로 처리하지 않는다")
    void propagatesS3UploadFailure(int statusCode) {
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(statusCode).message("S3 PDF 업로드 실패").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);

        assertThatThrownBy(() -> writer.upload(10L, PDF_CONTENT)).isSameAs(failure);
    }

    @Test
    @DisplayName("PDF 업로드 통신 오류를 성공으로 처리하지 않는다")
    void propagatesConnectionFailure() {
        SdkClientException failure = SdkClientException.create("S3 연결 실패");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);

        assertThatThrownBy(() -> writer.upload(10L, PDF_CONTENT)).isSameAs(failure);
    }
}
