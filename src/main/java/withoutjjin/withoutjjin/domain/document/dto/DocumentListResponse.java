package withoutjjin.withoutjjin.domain.document.dto;

import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;

import java.time.LocalDateTime;

public record DocumentListResponse(
        Long id,
        String title,
        Integer revision,
        LocalDateTime createdAt,
        // 최신 DocumentVersion 또는 DocumentConversionJob이 없으면 null이다.
        DocumentConversionJobStatus conversionStatus
) {
}
