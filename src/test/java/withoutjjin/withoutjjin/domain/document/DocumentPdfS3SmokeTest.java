package withoutjjin.withoutjjin.domain.document;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentQueryService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// RUN_DOCUMENT_PDF_SMOKE_TEST=true, DOCUMENT_PDF_SMOKE_JOB_ID=변환완료작업ID를 지정한다.
// 개발 DB의 기존 완료 작업과 실제 S3 PDF를 읽기만 하며 URL이나 인증 정보는 출력하지 않는다.
@SpringBootTest(properties = "aws.s3.bucket=${S3_BUCKET}")
@ActiveProfiles("test")
@Transactional(readOnly = true)
@Tag("s3-smoke")
@EnabledIfEnvironmentVariable(named = "RUN_DOCUMENT_PDF_SMOKE_TEST", matches = "true")
@Timeout(60)
class DocumentPdfS3SmokeTest {

    @Autowired private DocumentConversionJobRepository jobs;
    @Autowired private DocumentQueryService service;

    @Test
    @DisplayName("실제 완료 문서의 소속을 확인하고 발급한 URL로 S3 PDF를 열어 페이지 수를 대조한다")
    void readsCompletedPdfUsingAuthorizedPresignedUrl() throws Exception {
        String configuredId = System.getenv("DOCUMENT_PDF_SMOKE_JOB_ID");
        assertThat(configuredId).as("DOCUMENT_PDF_SMOKE_JOB_ID 설정이 필요합니다.").isNotBlank();
        var job = jobs.findById(Long.parseLong(configuredId)).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(DocumentConversionJobStatus.COMPLETED);
        var upload = job.getDocumentUpload();
        var document = upload.getDocument();
        var requester = upload.getCreatedBy();
        assertThat(service.getDocuments(upload.getWorkspace().getId(), requester.getIssuer(), requester.getSubject()))
                .anySatisfy(item -> {
                    assertThat(item.id()).isEqualTo(document.getId());
                    assertThat(item.conversionStatus()).isEqualTo(DocumentConversionJobStatus.COMPLETED);
                });
        var result = service.getPdfUrl(document.getId(), requester.getIssuer(), requester.getSubject());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var response = client.send(HttpRequest.newBuilder(URI.create(result.pdfUrl()))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertThat(response.statusCode()).as("실제 S3 PDF 조회 HTTP 상태. 403이면 review-pdfs의 s3:GetObject 권한을 확인하세요.")
                    .isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
            assertThat(response.headers().firstValue("Content-Disposition").orElse("")).startsWith("inline");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
            byte[] bytes = body.readNBytes(64 * 1024 * 1024 + 1);
            assertThat(bytes.length).isBetween(1, 64 * 1024 * 1024);
            try (var pdf = Loader.loadPDF(bytes)) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(job.getDocumentVersion().getPageCount());
            }
            Path output = Path.of("build", "test-results", "s3-smoke", "document-" + document.getId() + ".pdf");
            Files.createDirectories(output.getParent());
            Files.write(output, bytes);
        }
    }
}
