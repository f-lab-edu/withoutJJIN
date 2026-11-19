package withoutjjin.withoutjjin.domain.document.dto;

import java.time.Instant;

public record PdfUrlResponse(String pdfUrl, Instant expiresAt) {
}
