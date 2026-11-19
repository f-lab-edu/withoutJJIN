package withoutjjin.withoutjjin.domain.document.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionDeletionService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/versions")
public class DocumentVersionDeletionController {

    private final DocumentVersionDeletionService deletionService;

    @DeleteMapping("/{documentVersionId}")
    public ResponseEntity<Void> deleteVersion(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        deletionService.deleteVersion(workspaceId, documentId, documentVersionId,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
