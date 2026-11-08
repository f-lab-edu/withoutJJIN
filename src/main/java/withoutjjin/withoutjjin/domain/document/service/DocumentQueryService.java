package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentQueryService {

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public List<DocumentListResponse> getDocuments(Long workspaceId, String issuer, String subject) {
        workspaceService.getWorkSpaceForMember(workspaceId, issuer, subject);
        return documentRepository.findAllWithLatestConversionStatusByWorkspaceId(workspaceId);
    }
}
