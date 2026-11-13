package withoutjjin.withoutjjin.domain.feedback.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.feedback.dto.CreateDocumentFeedbackRequest;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackResponse;
import withoutjjin.withoutjjin.domain.feedback.service.DocumentFeedbackService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/document-versions")
public class DocumentFeedbackController {

    private final DocumentFeedbackService documentFeedbackService;

    @PostMapping(value = "/{documentVersionId}/feedback",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentFeedbackResponse> createFeedback(
            @PathVariable @Positive Long documentVersionId,
            @Valid @RequestBody CreateDocumentFeedbackRequest request,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        DocumentFeedbackResponse response = documentFeedbackService.createFeedback(documentVersionId, request,
                oidcUser.getIssuer().toString(), oidcUser.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
