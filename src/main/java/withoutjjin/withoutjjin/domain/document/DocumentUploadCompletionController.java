package withoutjjin.withoutjjin.domain.document;

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
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/uploads")
public class DocumentUploadCompletionController {

    private final DocumentUploadCompletionService completionService;

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<CompleteUploadResponse> completeUpload(
            @PathVariable @Positive Long uploadId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        CompleteUploadResponse response = completionService.completeUpload(
                uploadId, oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
