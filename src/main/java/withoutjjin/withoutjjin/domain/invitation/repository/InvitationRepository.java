package withoutjjin.withoutjjin.domain.invitation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    boolean existsByWorkspaceIdAndTargetEmailAndInvitationStatusAndExpiresAtAfter(Long workspaceId, String targetEmail, InvitationStatus attr0, LocalDateTime expiresAtAfter);
}
