package withoutjjin.withoutjjin.domain.document.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.storage.DocumentStorageKeyGenerator;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectReader;
import withoutjjin.withoutjjin.domain.document.storage.S3PdfWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DocumentConversionFlowTest {

    @Test
    @DisplayName("S3의 DOCX를 변환 요청에 담고 PDF 응답을 S3에 업로드한다")
    void connectsDownloadConversionAndUpload() throws IOException {
        String originalKey = "workspaces/10/uploads/original-docx";
        byte[] docx = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();
        byte[] pdf = pdfWithTwoPages();
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) docx.length).build(),
                new ByteArrayInputStream(docx)));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://converter.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://converter.test/forms/libreoffice/convert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsBytes())
                        .containsSubsequence(docx))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        DocumentConversionService service = new DocumentConversionService(
                new S3ObjectReader(s3Client, "ftfm-test-bucket"),
                new WordPdfConverter(builder.build()),
                new S3PdfWriter(s3Client, new DocumentStorageKeyGenerator(), "ftfm-test-bucket"));

        StoredPdf result = service.convertAndStore(10L, originalKey);

        ArgumentCaptor<GetObjectRequest> getCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        var order = inOrder(s3Client);
        order.verify(s3Client).getObject(getCaptor.capture());
        order.verify(s3Client).putObject(putCaptor.capture(), bodyCaptor.capture());
        order.verifyNoMoreInteractions();
        assertThat(getCaptor.getValue().bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(getCaptor.getValue().key()).isEqualTo(originalKey);
        assertThat(putCaptor.getValue().bucket()).isEqualTo("ftfm-test-bucket");
        assertThat(putCaptor.getValue().key()).isEqualTo(result.objectKey()).isNotEqualTo(originalKey);
        assertThat(putCaptor.getValue().contentType()).isEqualTo("application/pdf");
        try (InputStream uploaded = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            assertThat(uploaded.readAllBytes()).containsExactly(pdf);
        }
        assertThat(result.sizeBytes()).isEqualTo(pdf.length);
        assertThat(result.pageCount()).isEqualTo(2);
        server.verify();
    }

    private static byte[] pdfWithTwoPages() throws IOException {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.addPage(new PDPage());
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
