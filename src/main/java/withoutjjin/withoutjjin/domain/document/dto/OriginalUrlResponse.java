package withoutjjin.withoutjjin.domain.document.dto;

import java.time.Instant;

public record OriginalUrlResponse(Long documentVersionId, Integer versionNumber,
                                  String originalFileName, String downloadUrl, Instant expiresAt) {
}
