package withoutjjin.withoutjjin.domain.workspace.controller;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceMemberBanResponse;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceMemberService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class WorkspaceMemberController {
    private final WorkspaceMemberService memberService;

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long userId, @AuthenticationPrincipal OidcUser user) {
        memberService.removeMember(workspaceId, userId, user.getIssuer().toString(), user.getSubject());
        return noContent();
    }

    @DeleteMapping("/members/me")
    public ResponseEntity<Void> leaveWorkspace(@PathVariable @Positive Long workspaceId,
            @AuthenticationPrincipal OidcUser user) {
        memberService.leaveWorkspace(workspaceId, user.getIssuer().toString(), user.getSubject());
        return noContent();
    }

    @GetMapping("/member-bans")
    public ResponseEntity<List<WorkspaceMemberBanResponse>> getMemberBans(@PathVariable @Positive Long workspaceId,
            @AuthenticationPrincipal OidcUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(memberService.getMemberBans(workspaceId, user.getIssuer().toString(), user.getSubject()));
    }

    @DeleteMapping("/member-bans/{userId}")
    public ResponseEntity<Void> unbanMember(@PathVariable @Positive Long workspaceId,
            @PathVariable @Positive Long userId, @AuthenticationPrincipal OidcUser user) {
        memberService.unbanMember(workspaceId, userId, user.getIssuer().toString(), user.getSubject());
        return noContent();
    }

    private ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
