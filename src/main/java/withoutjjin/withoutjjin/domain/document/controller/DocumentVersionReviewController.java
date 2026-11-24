package withoutjjin.withoutjjin.domain.document.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionReviewResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionReviewService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/versions/{documentVersionId}")
public class DocumentVersionReviewController {

    private final DocumentVersionReviewService reviewService;

    @PutMapping("/review-completion")
    public ResponseEntity<DocumentVersionReviewResponse> completeReview(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        DocumentVersionReviewResponse response = reviewService.completeReview(workspaceId, documentId, documentVersionId,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/reviews")
    public ResponseEntity<List<DocumentVersionReviewResponse>> getReviews(
            @PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long documentId,
            @PathVariable @Positive Long documentVersionId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        List<DocumentVersionReviewResponse> response = reviewService.getReviews(workspaceId, documentId, documentVersionId,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
