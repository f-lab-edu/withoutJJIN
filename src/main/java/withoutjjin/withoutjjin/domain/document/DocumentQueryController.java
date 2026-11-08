package withoutjjin.withoutjjin.domain.document;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentQueryService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class DocumentQueryController {

    private final DocumentQueryService documentQueryService;

    @GetMapping("/workspaces/{workspaceId}/documents")
    public ResponseEntity<List<DocumentListResponse>> getDocuments(
            @PathVariable @Positive Long workspaceId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documentQueryService.getDocuments(workspaceId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }
}
