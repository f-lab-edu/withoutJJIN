package withoutjjin.withoutjjin.domain.document.storage;


import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DocumentStorageKeyGenerator { // 파일키 생성자

    public String generate(Long workspaceId) {
        validateWorkspaceId(workspaceId);
//ex) workspaces/10/uploads/550e8400-e29b-41d4-a716-446655440000
        return "workspaces/" + workspaceId
                + "/uploads/" + UUID.randomUUID();
    }

    public String generatePdf(Long workspaceId) {
        validateWorkspaceId(workspaceId);
        return "workspaces/" + workspaceId
                + "/review-pdfs/" + UUID.randomUUID() + ".pdf";
    }

    private void validateWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new IllegalArgumentException(
                    "워크스페이스 번호는 양수여야 합니다."
            );
        }
    }
}
