package withoutjjin.withoutjjin.domain.document.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

@Controller
public class DocumentHistoryPageController {
    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/history")
    public ResponseEntity<Resource> historyPage() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/document-history.html"));
    }
}
