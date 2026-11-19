package withoutjjin.withoutjjin.domain.document.dto;

import java.time.Instant;

public record PdfUrlResponse(Long documentVersionId, Integer versionNumber, String pdfUrl, Instant expiresAt) {
}
