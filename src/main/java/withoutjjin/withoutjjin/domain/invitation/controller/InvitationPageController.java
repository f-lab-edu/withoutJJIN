package withoutjjin.withoutjjin.domain.invitation.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

@Controller
public class InvitationPageController {

    @GetMapping("/invite")
    public ResponseEntity<Resource> invitationPage() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ClassPathResource("static/invite.html"));
    }

    // 이 경로는 인증이 필요하다. Security가 로그인 전 요청을 저장하고 성공 후 돌려보낸다.
    @GetMapping("/invite/continue")
    public ResponseEntity<Void> continueInvitation() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, "/invite")
                .build();
    }
}
