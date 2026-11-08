package withoutjjin.withoutjjin.domain.document.conversion;

// S3 업로드가 성공한 PDF의 결과 값이다. StoreFile 엔티티를 생성하거나 저장하지 않는다.
public record StoredPdf(String objectKey, long sizeBytes, int pageCount) {
}
