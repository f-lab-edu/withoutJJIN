package withoutjjin.withoutjjin.domain.invitation.dto;

import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import java.time.LocalDateTime;

// 생성·개별 조회에서 생성자에게만 반환한다. 목록에는 InvitationStatusResponse를 사용한다.
public record InvitationResponse(Long id, Long workspaceId, Long invitedBy,
                                 InvitationStatus invitationStatus, LocalDateTime expiresAt,
                                 LocalDateTime createdAt, String token) {
    @Override
    public String toString() {
        return "InvitationResponse[id=" + id + ", token=***]";
    }
}
