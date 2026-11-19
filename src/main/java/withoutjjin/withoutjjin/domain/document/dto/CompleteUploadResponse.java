package withoutjjin.withoutjjin.domain.document.dto;

public record CompleteUploadResponse(
        Long uploadId,
        Long documentId,
        Long documentVersionId
) {
}
