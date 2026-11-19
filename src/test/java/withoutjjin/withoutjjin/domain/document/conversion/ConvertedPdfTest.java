package withoutjjin.withoutjjin.domain.document.conversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ConvertedPdfTest {

    @Test
    @DisplayName("서로 다른 배열이어도 PDF 바이트 내용과 페이지 수가 같으면 같은 값이다")
    void comparesByteContentsAndPageCount() {
        ConvertedPdf first = new ConvertedPdf(new byte[]{1, 2, 3}, 1);
        ConvertedPdf second = new ConvertedPdf(new byte[]{1, 2, 3}, 1);

        assertThat(first.content()).isNotSameAs(second.content());
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("PDF 크기가 같아도 바이트 내용이 다르면 다른 값이다")
    void distinguishesDifferentByteContents() {
        ConvertedPdf first = new ConvertedPdf(new byte[]{1, 2, 3}, 1);
        ConvertedPdf second = new ConvertedPdf(new byte[]{1, 2, 4}, 1);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("PDF 바이트 내용이 같아도 페이지 수가 다르면 다른 값이다")
    void distinguishesDifferentPageCounts() {
        ConvertedPdf first = new ConvertedPdf(new byte[]{1, 2, 3}, 1);
        ConvertedPdf second = new ConvertedPdf(new byte[]{1, 2, 3}, 2);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("같은 내용과 페이지 수를 가진 ConvertedPdf는 HashSet에서 중복으로 처리한다")
    void deduplicatesEquivalentValuesInHashSet() {
        ConvertedPdf first = new ConvertedPdf(new byte[]{1, 2, 3}, 1);
        ConvertedPdf second = new ConvertedPdf(new byte[]{1, 2, 3}, 1);
        HashSet<ConvertedPdf> values = new HashSet<>();
        values.add(first);
        values.add(second);

        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(values).hasSize(1);
        assertThat(values.contains(new ConvertedPdf(new byte[]{1, 2, 3}, 1))).isTrue();
    }

    @Test
    @DisplayName("문자열에는 PDF 전체 바이트 대신 데이터 크기와 페이지 수를 표시한다")
    void describesMetadataWithoutDumpingContent() {
        byte[] content = {1, 2, 3};
        ConvertedPdf pdf = new ConvertedPdf(content, 2);

        assertThat(pdf.toString()).contains("contentLength=3", "pageCount=2")
                .doesNotContain(Arrays.toString(content), "[B@");
    }
}
