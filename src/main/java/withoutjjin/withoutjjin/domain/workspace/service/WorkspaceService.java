package withoutjjin.withoutjjin.domain.workspace.service;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
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
}
