package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.Abortable;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class S3ObjectReaderDownloadTest {

    private static final int MAX_DOCX_BYTES = 20 * 1024 * 1024;
    private static final String OBJECT_KEY = "workspaces/10/uploads/source.docx";

    private final S3Client s3Client = mock(S3Client.class);
    private final S3ObjectReader reader = new S3ObjectReader(s3Client, "ftfm-test-bucket");
    private final Abortable abortable = mock(Abortable.class);

    @Test
    @DisplayName("설정된 버킷과 저장 키로 DOCX를 다운로드하고 응답을 닫는다")
    void downloadsDocxFromConfiguredBucketAndKey() throws IOException {
        byte[] expected = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();
        InputStream input = spy(new ByteArrayInputStream(expected));
        stubResponse((long) expected.length, input);

        byte[] downloaded = reader.downloadDocx(OBJECT_KEY);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(captor.getValue().key()).isEqualTo(OBJECT_KEY);
        assertThat(downloaded).containsExactly(expected);
        verify(input).close();
        verifyNoInteractions(abortable);
    }

    @Test
    @DisplayName("정확히 20MiB인 DOCX 데이터를 허용한다")
    void acceptsDocxAtSizeLimit() {
        byte[] expected = new byte[MAX_DOCX_BYTES];
        stubResponse((long) expected.length, new ByteArrayInputStream(expected));

        assertThat(reader.downloadDocx(OBJECT_KEY)).hasSize(MAX_DOCX_BYTES);
        verifyNoInteractions(abortable);
    }

    @Test
    @DisplayName("Content-Length가 20MiB를 초과하면 본문을 읽기 전에 다운로드를 중단한다")
    void rejectsOversizedContentLengthBeforeReadingBody() throws IOException {
        InputStream input = mock(InputStream.class);
        stubResponse((long) MAX_DOCX_BYTES + 1, input);

        assertThatThrownBy(() -> reader.downloadDocx(OBJECT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("20MiB");

        verify(input, never()).read(any(byte[].class), anyInt(), anyInt());
        verify(abortable).abort();
        verify(input, atLeastOnce()).close();
    }

    @ParameterizedTest
    @MethodSource("missingOrUnderstatedContentLengths")
    @DisplayName("Content-Length가 없거나 작게 표시되어도 20MiB 초과 다운로드를 중단한다")
    void limitsActualBytesRead(Long contentLength) throws IOException {
        InputStream input = mock(InputStream.class);
        AtomicInteger bytesRead = new AtomicInteger();
        // 끝없이 데이터를 보내는 응답을 준비하여, 전체 본문을 읽지 않는지 확인한다.
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
            int requested = invocation.getArgument(2);
            bytesRead.addAndGet(requested);
            return requested;
        });
        stubResponse(contentLength, input);

        assertThatThrownBy(() -> reader.downloadDocx(OBJECT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("20MiB");

        assertThat(bytesRead.get()).isEqualTo(MAX_DOCX_BYTES + 1);
        verify(abortable).abort();
        verify(input, atLeastOnce()).close();
    }

    static Stream<Long> missingOrUnderstatedContentLengths() {
        return Stream.of(null, 1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 403, 500})
    @DisplayName("GetObject의 파일 없음·접근 거부·S3 서버 오류를 전달한다")
    void propagatesS3DownloadFailure(int statusCode) {
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("S3 다운로드 실패")
                .build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> reader.downloadDocx(OBJECT_KEY)).isSameAs(failure);
    }

    @Test
    @DisplayName("GetObject의 통신 오류를 전달한다")
    void propagatesConnectionFailure() {
        SdkClientException failure = SdkClientException.create("S3 연결 실패");
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> reader.downloadDocx(OBJECT_KEY)).isSameAs(failure);
    }

    @Test
    @DisplayName("본문 읽기 실패 시 다운로드를 중단하고 원인 예외를 전달한다")
    void abortsDownloadWhenBodyReadFails() throws IOException {
        InputStream input = mock(InputStream.class);
        IOException failure = new IOException("응답 읽기 실패");
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenThrow(failure);
        stubResponse(1024L, input);

        assertThatThrownBy(() -> reader.downloadDocx(OBJECT_KEY))
                .isInstanceOf(SdkClientException.class)
                .hasCause(failure);

        verify(abortable).abort();
        verify(input, atLeastOnce()).close();
    }

    private void stubResponse(Long contentLength, InputStream input) {
        GetObjectResponse response = GetObjectResponse.builder().contentLength(contentLength).build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                response, AbortableInputStream.create(input, abortable));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
    }
}
