package withoutjjin.withoutjjin.domain.document.dto;

import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;

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
        DocumentConversionJobStatus conversionStatus,
        DocumentConversionFailureStage failureStage,
        DocumentConversionFailureCode failureCode,
        LocalDateTime failedAt
) {
    public DocumentVersionListResponse {
        // 재시도 중이거나 완료한 버전에는 이전 실패 정보를 노출하지 않는다.
        if (conversionStatus != DocumentConversionJobStatus.FAILED) {
            failureStage = null;
            failureCode = null;
            failedAt = null;
        }
    }
}
