package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.conversion.ConvertedPdf;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectReader;
import withoutjjin.withoutjjin.domain.document.storage.S3PdfWriter;

@Service
@RequiredArgsConstructor
public class DocumentConversionService {

    private final S3ObjectReader s3ObjectReader;
    private final WordPdfConverter wordPdfConverter;
    private final S3PdfWriter s3PdfWriter;

    // 호출한 코드에서 확인한 StoreFile.objectKey를 전달받는다.
    // S3 다운로드·변환·업로드를 기다리는 동안 호출자의 DB 트랜잭션도 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StoredPdf convertAndStore(Long workspaceId, String originalObjectKey) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new IllegalArgumentException("워크스페이스 번호는 양수여야 합니다.");
        }
        if (originalObjectKey == null || originalObjectKey.isBlank()) {
            throw new IllegalArgumentException("DOCX의 S3 저장 키가 필요합니다.");
        }

        byte[] docxContent = s3ObjectReader.downloadDocx(originalObjectKey);
        ConvertedPdf convertedPdf = wordPdfConverter.convert(docxContent);
        String pdfObjectKey = s3PdfWriter.upload(workspaceId, convertedPdf.content());

        return new StoredPdf(pdfObjectKey, convertedPdf.content().length, convertedPdf.pageCount());
    }
}
