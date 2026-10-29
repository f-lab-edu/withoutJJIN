package withoutjjin.withoutjjin.domain.workspace.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkspaceDetailResponse(
        Long id,
        String name,
        Long createdBy,
        LocalDateTime createdAt,
        List<Member> members
) {
    // 팀 화면에 필요한 회원 정보만 반환한다. 이메일·Google 계정 식별자는 포함하지 않는다.
    public record Member(Long userId, String displayName, LocalDateTime joinedAt) {
    }
}
