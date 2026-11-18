package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3PdfWriter {

    private static final int MAX_PDF_BYTES = 64 * 1024 * 1024;

    private final S3Client s3Client;
    private final DocumentStorageKeyGenerator storageKeyGenerator;
    private final String bucket;

    public S3PdfWriter(
            S3Client s3Client,
            DocumentStorageKeyGenerator storageKeyGenerator,
            @Value("${aws.s3.bucket}") String bucket
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("S3 버킷 이름을 설정해야 합니다.");
        }
        this.s3Client = s3Client;
        this.storageKeyGenerator = storageKeyGenerator;
        this.bucket = bucket;
    }

    // WordPdfConverter에서 검증한 PDF 데이터를 전달받는다. PDF 구조를 다시 검사하지 않는다.
    public String upload(Long workspaceId, byte[] pdfContent) {
        if (pdfContent == null || pdfContent.length == 0 || pdfContent.length > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF 데이터는 비어 있지 않고 64MiB 이하여야 합니다.");
        }

        String objectKey = storageKeyGenerator.generatePdf(workspaceId);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType("application/pdf")
                // 우연히 같은 키가 생성되어도 기존 S3 파일을 덮어쓰지 않는다.
                .ifNoneMatch("*")
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(pdfContent));
        return objectKey;
    }
}
