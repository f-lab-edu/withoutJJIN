package withoutjjin.withoutjjin.domain.workspace.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceDetailResponse;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AppUserService appUserService;

    @Transactional
    public Workspace createWorkspace(
            String name,
            String issuer,
            String subject
    ) {
        AppUser creator = appUserService.getByIssuerAndSubject(issuer, subject);

        Workspace workspace = workspaceRepository.save(new Workspace(name, creator));

        workspaceMemberRepository.save(new WorkspaceMember(workspace, creator));

        return workspace;
    }


    @Transactional(readOnly = true)
    public List<Workspace> getMyWorkspaces(String issuer, String subject) {
        AppUser user = appUserService.getByIssuerAndSubject(issuer, subject);
        return workspaceMemberRepository.findAllByUserId(user.getId())
                .stream()
                .map(WorkspaceMember::getWorkspace)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDetailResponse getWorkspace(Long workspaceId, String issuer, String subject) {
        AppUser user = appUserService.getByIssuerAndSubject(issuer, subject);
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "워크스페이스를 찾을 수 없습니다."));
        if (!workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "워크스페이스 멤버만 조회할 수 있습니다.");
        }
        // 회원을 함께 조회하고 트랜잭션 안에서 DTO 변환을 마친다.
        List<WorkspaceDetailResponse.Member> members = workspaceMemberRepository
                .findAllWithUserByWorkspaceId(workspaceId).stream()
                .map(member -> new WorkspaceDetailResponse.Member(member.getUser().getId(),
                        member.getUser().getDisplayName(), member.getJoinedAt()))
                .toList();
        return new WorkspaceDetailResponse(workspace.getId(), workspace.getName(),
                workspace.getCreatedBy().getId(), workspace.getCreatedAt(), members);
    }







}
