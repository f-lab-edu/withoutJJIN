package withoutjjin.withoutjjin.domain.workspace.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import withoutjjin.withoutjjin.domain.workspace.dto.CreateWorkspaceRequest;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceResponse;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

@Controller
@RequiredArgsConstructor
@RequestMapping(value = "api/v1")
public class WorkspaceController {

    private final WorkspaceService workspaceService;


    @PostMapping("/workspaces")
    public ResponseEntity<WorkspaceResponse> workspaceCreate(
            @AuthenticationPrincipal OidcUser oidcUser,
            @Valid @RequestBody CreateWorkspaceRequest request) {
        // Service가 팀 생성 처리
        Workspace workspace = workspaceService.createWorkspace(
                request.name(),
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject()
        );

// 처리 결과로 응답 DTO 생성
        WorkspaceResponse response =
                new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                        workspace.getCreatedBy().getId(),
                        workspace.getCreatedAt()
        );

        return ResponseEntity.status(201).body(response);
    }
}
