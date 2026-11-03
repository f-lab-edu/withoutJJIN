package withoutjjin.withoutjjin.domain.document.dto;


import java.time.Instant;

public record CreateUploadUrlResponse (
        String uploadUrl,
        String storageKey,
        Instant expiresAt
){}
