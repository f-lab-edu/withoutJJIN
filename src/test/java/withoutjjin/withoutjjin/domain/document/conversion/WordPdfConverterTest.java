package withoutjjin.withoutjjin.domain.document.conversion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WordPdfConverterTest {

    private static final String CONVERT_URL = "http://conversion.test/forms/libreoffice/convert";
    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024;
    private MockRestServiceServer server;
    private WordPdfConverter converter;
    private byte[] docx;

    @BeforeEach
    void setUp() throws IOException {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://conversion.test");
        server = MockRestServiceServer.bindTo(builder).build();
        converter = new WordPdfConverter(builder.build());
        docx = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    @DisplayName("DOCX를 files 항목으로 전송하고 검증한 PDF 데이터와 페이지 수를 반환한다")
    void sendsDocxMultipartAndReturnsValidatedPdf() throws IOException {
        byte[] pdf = pdfWithPages(2);
        server.expect(requestTo(CONVERT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", "application/pdf"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType().getParameter("boundary")).isNotBlank();
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    String parts = new String(body, StandardCharsets.ISO_8859_1);
                    assertThat(parts).contains("name=\"files\"", "filename=\"document.docx\"",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                    assertThat(body).containsSubsequence(docx);
                })
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        ConvertedPdf result = converter.convert(docx);

        assertThat(result.content()).isEqualTo(pdf);
        assertThat(result.pageCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("비어 있는 DOCX는 HTTP 요청 전에 거부한다")
    void rejectsMissingDocxBeforeRequest(byte[] content) {
        assertThatThrownBy(() -> converter.convert(content))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20MiB");
    }

    @Test
    @DisplayName("20MiB를 초과한 DOCX는 HTTP 요청 전에 거부한다")
    void rejectsOversizedDocxBeforeRequest() {
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> converter.convert(oversized))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20MiB");
    }

    @Test
    @DisplayName("정확히 20MiB인 DOCX 데이터는 변환 요청을 허용한다")
    void acceptsMaximumInputSize() throws IOException {
        server.expect(requestTo(CONVERT_URL)).andRespond(withSuccess(pdfWithPages(1), MediaType.APPLICATION_PDF));
        assertThat(converter.convert(new byte[20 * 1024 * 1024]).pageCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/zip", "application/octet-stream"})
    @DisplayName("PDF가 아닌 Content-Type 응답을 거부한다")
    void rejectsNonPdfContentType(String contentType) throws IOException {
        server.expect(requestTo(CONVERT_URL)).andRespond(withSuccess(pdfWithPages(1), MediaType.parseMediaType(contentType)));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("application/pdf");
    }

    @Test
    @DisplayName("Content-Type이 없는 응답을 거부한다")
    void rejectsMissingContentType() throws IOException {
        server.expect(requestTo(CONVERT_URL)).andRespond(withStatus(HttpStatus.OK).body(pdfWithPages(1)));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("application/pdf");
    }

    @Test
    @DisplayName("문법이 잘못된 Content-Type은 변환 예외로 전달한다")
    void rejectsMalformedContentType() {
        server.expect(requestTo(CONVERT_URL)).andRespond(withStatus(HttpStatus.OK).header("Content-Type", "invalid-media-type"));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("Content-Type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%PDF-1.7\nbroken-pdf", "this is not a PDF"})
    @DisplayName("application/pdf 응답이어도 실제 PDF를 열 수 없으면 거부한다")
    void rejectsInvalidPdfBody(String body) {
        server.expect(requestTo(CONVERT_URL)).andRespond(withSuccess(body.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_PDF));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("PDF로 열 수 없습니다");
    }

    @Test
    @DisplayName("페이지가 없는 PDF를 거부한다")
    void rejectsPdfWithoutPages() throws IOException {
        server.expect(requestTo(CONVERT_URL)).andRespond(withSuccess(pdfWithPages(0), MediaType.APPLICATION_PDF));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("페이지가 없습니다");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 429, 500, 503, 302, 307})
    @DisplayName("변환 서버의 실패 상태를 전달하고 원격 오류 본문은 노출하지 않는다")
    void rejectsUnsuccessfulResponseWithoutExposingRemoteBody(int status) {
        server.expect(requestTo(CONVERT_URL)).andRespond(withStatus(HttpStatus.valueOf(status)).body("private-document-content"));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining("private-document-content");
    }

    @Test
    @DisplayName("응답 크기가 64MiB를 초과한다고 선언되면 PDF를 읽기 전에 거부한다")
    void rejectsOversizedDeclaredResponse() throws IOException {
        server.expect(requestTo(CONVERT_URL)).andRespond(withSuccess(pdfWithPages(1), MediaType.APPLICATION_PDF)
                .header("Content-Length", String.valueOf(MAX_PDF_BYTES + 1)));
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("64MiB");
    }

    @Test
    @DisplayName("Content-Length가 없어도 64MiB를 넘겨 무제한으로 읽지 않는다")
    void boundsResponseReadingWithoutContentLength() {
        AtomicInteger readBytes = new AtomicInteger();
        InputStream oversized = new InputStream() {
            private int remaining = MAX_PDF_BYTES + 4096;
            @Override
            public int read() {
                if (remaining == 0) return -1;
                remaining--;
                readBytes.incrementAndGet();
                return 0;
            }
            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = Math.min(length, remaining);
                Arrays.fill(buffer, offset, offset + count, (byte) 0);
                remaining -= count;
                readBytes.addAndGet(count);
                return count;
            }
        };
        server.expect(requestTo(CONVERT_URL)).andRespond(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(oversized, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_PDF);
            return response;
        });
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class).hasMessageContaining("64MiB");
        assertThat(readBytes.get()).isEqualTo(MAX_PDF_BYTES + 1);
    }

    @Test
    @DisplayName("변환 서버 연결 실패를 변환 예외로 전달한다")
    void mapsConnectionFailure() {
        server.expect(requestTo(CONVERT_URL)).andRespond(request -> {throw new ConnectException("connection refused");});
        assertThatThrownBy(() -> converter.convert(docx))
                .isInstanceOf(WordPdfConversionException.class)
                .hasMessageContaining("연결하지 못했거나")
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    private static byte[] pdfWithPages(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
