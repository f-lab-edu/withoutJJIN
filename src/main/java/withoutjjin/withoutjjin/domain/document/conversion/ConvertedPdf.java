package withoutjjin.withoutjjin.domain.document.conversion;

// 검증한 PDF 데이터와 페이지 수. S3 저장과 DocumentVersion 연결은 호출하는 쪽에서 처리한다.
public record ConvertedPdf(byte[] content, int pageCount) {
}
