package withoutjjin.withoutjjin.domain.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces")
public class DocumentUploadController {

    private final DocumentService documentService;

    @PostMapping("/{workspaceId}/uploads")
    public ResponseEntity<CreateUploadUrlResponse> createUploadUrl(
            @PathVariable @Positive Long workspaceId,
            @AuthenticationPrincipal OidcUser oidcUser,
            @Valid @RequestBody CreateUploadUrlRequest request
    ) {
        CreateUploadUrlResponse response = documentService.createUploadUrl(
                workspaceId,
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject(),
                request
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/{workspaceId}/documents/{documentId}/uploads")
    public ResponseEntity<CreateUploadUrlResponse> createVersionUploadUrl(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @AuthenticationPrincipal OidcUser oidcUser,
            @Valid @RequestBody CreateUploadUrlRequest request
    ) {
        CreateUploadUrlResponse response = documentService.createVersionUploadUrl(
                workspaceId, documentId, oidcUser.getIssuer().toString(), oidcUser.getSubject(), request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
