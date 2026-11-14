package withoutjjin.withoutjjin.domain.document.dto;

import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;

import java.time.LocalDateTime;

public record DocumentVersionListResponse(
        Long id,
        Long documentId,
        Integer versionNumber,
        String originalFileName,
        Long fileSize,
        Integer pageCount,
        Long uploadedBy,
        String uploadedByDisplayName,
        LocalDateTime createdAt,
        // DocumentConversionJob이 아직 없으면 null이다.
        DocumentConversionJobStatus conversionStatus
) {
}
