package withoutjjin.withoutjjin.domain.invitation.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    boolean existsByWorkspaceIdAndTargetEmailAndInvitationStatusAndExpiresAtAfter(
            Long workspaceId, String targetEmail, InvitationStatus invitationStatus,
            LocalDateTime expiresAtAfter
    );

    // 처리할 후보 ID만 조회한다. 실제 작업자가 처리 시작 전에 상태를 다시 확인해야 한다.
    // FAILED이면서 nextRetryAt이 null이면 최종 실패이므로 조회되지 않는다.
    @Query("""
            select i.id
            from Invitation i
            where i.invitationStatus = :invitationStatus
              and i.expiresAt > :now
              and i.encryptedToken is not null
              and (
                    i.emailSendStatus = :pendingStatus
                    or (i.emailSendStatus = :failedStatus and i.nextRetryAt <= :now)
              )
            order by i.id asc
            """)
    List<Long> findSendableIds(
            @Param("invitationStatus") InvitationStatus invitationStatus,
            @Param("pendingStatus") EmailSendStatus pendingStatus,
            @Param("failedStatus") EmailSendStatus failedStatus,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
