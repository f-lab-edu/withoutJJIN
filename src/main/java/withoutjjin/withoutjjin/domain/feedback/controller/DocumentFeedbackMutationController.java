package withoutjjin.withoutjjin.domain.feedback.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackResponse;
import withoutjjin.withoutjjin.domain.feedback.dto.UpdateDocumentFeedbackRequest;
import withoutjjin.withoutjjin.domain.feedback.service.DocumentFeedbackService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/feedback")
public class DocumentFeedbackMutationController {

    private final DocumentFeedbackService documentFeedbackService;

    @PatchMapping(value = "/{feedbackId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentFeedbackResponse> updateFeedback(
            @PathVariable @Positive Long feedbackId,
            @Valid @RequestBody UpdateDocumentFeedbackRequest request,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        DocumentFeedbackResponse response = documentFeedbackService.updateFeedback(feedbackId, request,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @DeleteMapping("/{feedbackId}")
    public ResponseEntity<Void> deleteFeedback(
            @PathVariable @Positive Long feedbackId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        documentFeedbackService.deleteFeedback(feedbackId,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
