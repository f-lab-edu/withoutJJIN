package withoutjjin.withoutjjin.domain.document.conversion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import withoutjjin.withoutjjin.global.config.DocumentConversionConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실행 예: RUN_GOTENBERG_TESTS=true를 설정하고 Gotenberg 컨테이너를 실행한다.
@Tag("gotenberg")
@EnabledIfEnvironmentVariable(named = "RUN_GOTENBERG_TESTS", matches = "true")
@Timeout(75)
class WordPdfConverterGotenbergTest {

    private final WordPdfConverter converter = new WordPdfConverter(new DocumentConversionConfig().wordPdfRestClient(
            URI.create(System.getenv().getOrDefault("GOTENBERG_TEST_URL", "http://localhost:3000")),
            Duration.ofSeconds(3), Duration.ofSeconds(65)));

    @Test
    @DisplayName("실제 Gotenberg에서 한글 DOCX를 PDF로 변환하고 한글·표 내용을 확인한다")
    void convertsKoreanDocxWithRealGotenberg() throws IOException {
        byte[] docx = new ClassPathResource("conversion/korean-document.docx").getContentAsByteArray();

        ConvertedPdf result = converter.convert(docx);

        assertThat(result.pageCount()).isEqualTo(1);
        try (var pdf = Loader.loadPDF(result.content())) {
            assertThat(new PDFTextStripper().getText(pdf)).contains(
                    "문서 변환 테스트", "한글 문단과 표", "검증 항목", "홍길동", "FTFM 2026");
        }
        Path output = Path.of("build", "test-results", "gotenberg", "korean-document.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, result.content());
    }

    @Test
    @DisplayName("실제 Gotenberg에서 손상된 DOCX 변환 실패를 성공으로 처리하지 않는다")
    void rejectsCorruptDocxWithRealGotenberg() {
        assertThatThrownBy(() -> converter.convert("PK\u0003\u0004broken-docx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WordPdfConversionException.class);
    }
}
