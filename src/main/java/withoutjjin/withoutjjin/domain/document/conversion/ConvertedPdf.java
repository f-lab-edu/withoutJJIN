package withoutjjin.withoutjjin.domain.document.conversion;

import java.util.Arrays;

// 검증한 PDF 데이터와 페이지 수. S3 저장과 DocumentVersion 연결은 호출하는 쪽에서 처리한다.
public record ConvertedPdf(byte[] content, int pageCount) {

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof ConvertedPdf pdf
                && pageCount == pdf.pageCount
                && Arrays.equals(content, pdf.content);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + Integer.hashCode(pageCount);
    }

    @Override
    public String toString() {
        // PDF 전체 바이트 대신 로그에서 확인할 크기와 페이지 수만 표시한다.
        String contentLength = content == null ? "null" : Integer.toString(content.length);
        return "ConvertedPdf[contentLength=" + contentLength + ", pageCount=" + pageCount + "]";
    }
}
