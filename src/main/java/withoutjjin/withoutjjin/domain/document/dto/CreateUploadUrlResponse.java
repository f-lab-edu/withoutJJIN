package withoutjjin.withoutjjin.domain.document.dto;

import java.time.Instant;

public record CreateUploadUrlResponse(
        Long uploadId,
        String uploadUrl,
        String storageKey,
        Instant expiresAt
) {
}
