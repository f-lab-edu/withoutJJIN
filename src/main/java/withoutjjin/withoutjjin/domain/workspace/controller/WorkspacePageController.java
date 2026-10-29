package withoutjjin.withoutjjin.domain.workspace.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

@Controller
public class WorkspacePageController {

    @GetMapping("/workspaces/{workspaceId}")
    public ResponseEntity<Resource> workspacePage() {
        // HTML 자체에는 팀 정보가 없다. 상세 API에서 로그인과 멤버십을 검사한다.
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("static/workspace.html"));
    }
}
