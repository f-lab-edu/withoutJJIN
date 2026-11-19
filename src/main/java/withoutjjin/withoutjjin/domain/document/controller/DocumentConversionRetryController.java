package withoutjjin.withoutjjin.domain.document.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionRetryService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/versions")
public class DocumentConversionRetryController {

    private final DocumentConversionRetryService retryService;

    @PostMapping("/{documentVersionId}/conversion-retry")
    public ResponseEntity<Void> retryConversion(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        retryService.retryConversion(workspaceId, documentId, documentVersionId,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
