package withoutjjin.withoutjjin.domain.invitation.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(name = "target_email", nullable = false)
    private String targetEmail;

    @Column(name = "token_hash", nullable = false,unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "invitation_status", nullable = false)
    private InvitationStatus invitationStatus;


    @Enumerated(EnumType.STRING)
    @Column(name = "email_send_status", nullable = false)
    private EmailSendStatus emailSendStatus;


    @Column(name = "retry_count", nullable = false)
    private Long retryCount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    private AppUser invitedBy;


    public Invitation(
            Workspace workspace,
            AppUser invitedBy,
            String targetEmail,
            String tokenHash,
            LocalDateTime expiresAt
    ) {
        this.workspace = workspace;
        this.invitedBy = invitedBy;
        this.targetEmail = targetEmail;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;

        this.invitationStatus = InvitationStatus.PENDING;
        this.emailSendStatus = EmailSendStatus.PENDING;
        this.retryCount = 0L;
    }


}
