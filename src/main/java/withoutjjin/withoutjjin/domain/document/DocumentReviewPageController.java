package withoutjjin.withoutjjin.domain.document;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

@Controller
public class DocumentReviewPageController {

    @GetMapping("/workspaces/{workspaceId}/documents/{documentId}/review")
    public ResponseEntity<Resource> reviewPage() {
        // 문서와 회원 정보는 HTML에 포함하지 않고 기존 API에서 권한 확인 후 조회한다.
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/document-review.html"));
    }
}
