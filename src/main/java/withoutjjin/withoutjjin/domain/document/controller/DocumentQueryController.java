package withoutjjin.withoutjjin.domain.document.controller;

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
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionListResponse;
import withoutjjin.withoutjjin.domain.document.dto.PdfUrlResponse;
import withoutjjin.withoutjjin.domain.document.dto.OriginalUrlResponse;
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

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions")
    public ResponseEntity<List<DocumentVersionListResponse>> getVersions(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documentQueryService.getVersions(workspaceId, documentId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/pdf-url")
    public ResponseEntity<PdfUrlResponse> getVersionPdfUrl(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documentQueryService.getVersionPdfUrl(workspaceId, documentId, documentVersionId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}/original-url")
    public ResponseEntity<OriginalUrlResponse> getVersionOriginalUrl(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documentQueryService.getVersionOriginalUrl(workspaceId, documentId, documentVersionId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }

    @GetMapping("/documents/{documentId}/pdf-url")
    public ResponseEntity<PdfUrlResponse> getPdfUrl(
            @PathVariable @Positive Long documentId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documentQueryService.getPdfUrl(documentId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }
}
