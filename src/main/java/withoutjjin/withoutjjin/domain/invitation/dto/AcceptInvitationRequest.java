package withoutjjin.withoutjjin.domain.invitation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank @Size(max = 256) String token
) {
    @Override
    public String toString() {
        return "AcceptInvitationRequest[token=***]";
    }
}
