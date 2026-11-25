package withoutjjin.withoutjjin.domain.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.dto.WorkspaceMemberBanResponse;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMemberBan;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberBanRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {
    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final WorkspaceMemberBanRepository bans;
    private final AppUserService users;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeMember(Long workspaceId, Long userId, String issuer, String subject) {
        Workspace workspace = lockedWorkspace(workspaceId);
        AppUser creator = users.getByIssuerAndSubject(issuer, subject);
        requireCreator(workspace, creator);
        if (workspace.getCreatedBy().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 생성자는 강퇴할 수 없습니다.");
        }
        Optional<WorkspaceMember> member = members.findByWorkspaceIdAndUserId(workspaceId, userId);
        boolean alreadyBanned = bans.existsByWorkspaceIdAndUserId(workspaceId, userId);
        if (member.isEmpty()) {
            if (alreadyBanned) return; // 동일 강퇴 요청은 기존 차단을 유지한다.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스 멤버를 찾을 수 없습니다.");
        }
        if (!alreadyBanned) {
            bans.save(new WorkspaceMemberBan(workspace, member.get().getUser(), creator));
        }
        members.delete(member.get());
        members.flush(); // 차단 저장과 멤버 제거는 같은 트랜잭션에서 성공하거나 롤백한다.
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void leaveWorkspace(Long workspaceId, String issuer, String subject) {
        Workspace workspace = lockedWorkspace(workspaceId);
        AppUser user = users.getByIssuerAndSubject(issuer, subject);
        if (workspace.getCreatedBy().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 생성자는 퇴장할 수 없습니다.");
        }
        WorkspaceMember member = members.findByWorkspaceIdAndUserId(workspaceId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 멤버만 퇴장할 수 있습니다."));
        members.delete(member);
        members.flush();
        // 본인 퇴장은 WorkspaceMemberBan을 만들거나 삭제하지 않는다.
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberBanResponse> getMemberBans(Long workspaceId, String issuer, String subject) {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow(this::workspaceNotFound);
        requireCreator(workspace, users.getByIssuerAndSubject(issuer, subject));
        return bans.findAllWithUsersByWorkspaceId(workspaceId).stream()
                .map(ban -> new WorkspaceMemberBanResponse(ban.getUser().getId(), ban.getUser().getDisplayName(),
                        ban.getBannedBy().getId(), ban.getCreatedAt()))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unbanMember(Long workspaceId, Long userId, String issuer, String subject) {
        Workspace workspace = lockedWorkspace(workspaceId);
        requireCreator(workspace, users.getByIssuerAndSubject(issuer, subject));
        bans.findByWorkspaceIdAndUserId(workspaceId, userId).ifPresent(bans::delete);
        bans.flush();
        // 차단 해제는 재가입 허용이며 WorkspaceMember를 자동 생성하지 않는다.
    }

    private Workspace lockedWorkspace(Long workspaceId) {
        return workspaces.findByIdForUpdate(workspaceId).orElseThrow(this::workspaceNotFound);
    }

    private void requireCreator(Workspace workspace, AppUser user) {
        if (!workspace.getCreatedBy().getId().equals(user.getId())
                || !members.existsByWorkspaceIdAndUserId(workspace.getId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 생성자만 멤버를 관리할 수 있습니다.");
        }
    }

    private ResponseStatusException workspaceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다.");
    }
}
