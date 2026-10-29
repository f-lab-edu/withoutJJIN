package withoutjjin.withoutjjin.domain.workspace.controller;


import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import withoutjjin.withoutjjin.domain.workspace.dto.CreateWorkspaceRequest;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceResponse;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceDetailResponse;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping("/workspaces/{workspaceId}")
    public ResponseEntity<WorkspaceDetailResponse> getWorkspace(
            @PathVariable @Positive Long workspaceId,
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(workspaceService.getWorkspace(workspaceId,
                        oidcUser.getIssuer().toString(), oidcUser.getSubject()));
    }


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
        WorkspaceResponse response = new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getCreatedBy().getId(),
                workspace.getCreatedAt()
        );

        return ResponseEntity.status(201).body(response);
    }


    @GetMapping("/workspaces")
    public ResponseEntity<List<WorkspaceResponse>> getMyWorkspaces(
            @AuthenticationPrincipal OidcUser oidcUser
    ) {
        List<WorkspaceResponse> response = workspaceService.getMyWorkspaces(
                        oidcUser.getIssuer().toString(),
                        oidcUser.getSubject()
                )
                .stream()
                .map(workspace -> new WorkspaceResponse(
                        workspace.getId(),
                        workspace.getName(),
                        workspace.getCreatedBy().getId(),
                        workspace.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }






}
