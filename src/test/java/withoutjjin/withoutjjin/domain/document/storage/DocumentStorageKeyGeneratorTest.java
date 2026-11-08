package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageKeyGeneratorTest {

    private final DocumentStorageKeyGenerator generator =
            new DocumentStorageKeyGenerator();

    @Test
    @DisplayName("같은 워크스페이스에서도 파일마다 서로 다른 저장 키를 생성한다")
    void generatesDifferentKeysForSameWorkspace() {

        String first = generator.generate(10L);
        String second = generator.generate(10L);

        assertThat(first).startsWith("workspaces/10/uploads/");
        assertThat(second).startsWith("workspaces/10/uploads/");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("PDF 저장 키는 원본 저장 키와 구분되고 호출마다 달라진다")
    void generatesPdfKeySeparateFromOriginal() {
        String original = generator.generate(10L);
        String firstPdf = generator.generatePdf(10L);
        String secondPdf = generator.generatePdf(10L);

        assertThat(original).startsWith("workspaces/10/uploads/");
        assertThat(firstPdf).startsWith("workspaces/10/review-pdfs/").endsWith(".pdf");
        assertThat(secondPdf).startsWith("workspaces/10/review-pdfs/").endsWith(".pdf");
        assertThat(firstPdf).isNotEqualTo(secondPdf).isNotEqualTo(original);
    }
}
