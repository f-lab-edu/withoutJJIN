package withoutjjin.withoutjjin.domain.document.dto;

import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;

import java.time.LocalDateTime;

public record DocumentListResponse(
        Long id,
        String title,
        // 현재 남은 DocumentVersion의 최대 versionNumber이며, 버전이 없으면 0이다.
        // 번호 발급에 사용하는 Document.revision과는 삭제 이후 값이 다를 수 있다.
        Integer revision,
        LocalDateTime createdAt,
        // 최신 DocumentVersion 또는 DocumentConversionJob이 없으면 null이다.
        DocumentConversionJobStatus conversionStatus,
        Long versionCount
) {
}
