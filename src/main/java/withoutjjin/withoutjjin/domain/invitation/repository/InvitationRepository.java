package withoutjjin.withoutjjin.domain.invitation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

}
