package withoutjjin.withoutjjin.domain.invitation.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "invitation", indexes = {@Index(
        name = "idx_invitation_email_schedule",
        columnList = "invitation_status,email_send_status,next_retry_at,expires_at"
), @Index(
        name = "idx_invitation_email_processing",
        columnList = "email_send_status,processing_started_at"
)})
public class Invitation {

    // 최초 발송을 제외하고 최대 3회 재시도한다.
    private static final long[] RETRY_DELAYS_SECONDS = {30, 60, 120};

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(name = "target_email", nullable = false)
    private String targetEmail;

    @Column(name = "token_hash", nullable = false,unique = true)
    private String tokenHash;

    // 기존 초대 행과 향후 발송 종료 후 삭제를 위해 DB에서는 null을 허용한다.
    @Column(name = "encrypted_token", length = 255)
    private String encryptedToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "invitation_status", nullable = false)
    private InvitationStatus invitationStatus;


    @Enumerated(EnumType.STRING)
    @Column(name = "email_send_status", nullable = false)
    private EmailSendStatus emailSendStatus;


    @Column(name = "retry_count", nullable = false)
    // 실제로 시작한 재시도 횟수. 최초 발송은 0이다.
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
            String encryptedToken,
            LocalDateTime expiresAt
    ) {
        this.workspace = workspace;
        this.invitedBy = invitedBy;
        this.targetEmail = targetEmail;
        this.tokenHash = tokenHash;
        this.encryptedToken = Objects.requireNonNull(encryptedToken, "발송용 토큰 암호문이 필요합니다.");
        this.expiresAt = expiresAt;

        this.invitationStatus = InvitationStatus.PENDING;
        this.emailSendStatus = EmailSendStatus.PENDING;
        this.retryCount = 0L;
    }

    public void startProcessing(LocalDateTime now) {
        Objects.requireNonNull(now, "처리 시작 시각이 필요합니다.");
        if (!isReadyToSend(now)) {
            throw new IllegalStateException("발송 가능한 초대의 처리만 시작할 수 있습니다.");
        }

        if (emailSendStatus == EmailSendStatus.FAILED) {
            retryCount++;
        }

        emailSendStatus = EmailSendStatus.PROCESSING;
        processingStartedAt = now;
        nextRetryAt = null;
    }

    public boolean isReadyToSend(LocalDateTime now) {
        Objects.requireNonNull(now, "조회 시각이 필요합니다.");
        if (invitationStatus != InvitationStatus.PENDING || !expiresAt.isAfter(now)
                || encryptedToken == null || encryptedToken.isBlank()) {
            return false;
        }
        return emailSendStatus == EmailSendStatus.PENDING
                || (emailSendStatus == EmailSendStatus.FAILED && nextRetryAt != null
                && !nextRetryAt.isAfter(now) && retryCount < RETRY_DELAYS_SECONDS.length);
    }

    public void markSubmitted() {
        ensureProcessing(); // 처리 상태인지 확인

        // SUBMITTED는 메일 서비스의 요청 접수 성공이며, 메일함 배달 완료는 아니다.
        emailSendStatus = EmailSendStatus.SUBMITTED;
        processingStartedAt = null;
        nextRetryAt = null;
        encryptedToken = null;
    }

    public void markFailed(LocalDateTime now, boolean retryable) {
        Objects.requireNonNull(now, "실패 시각이 필요합니다.");
        ensureProcessing(); // 준비상태인지 확인

        emailSendStatus = EmailSendStatus.FAILED;
        processingStartedAt = null;
        nextRetryAt = null;

        if (retryable && retryCount < RETRY_DELAYS_SECONDS.length
                && invitationStatus == InvitationStatus.PENDING && expiresAt.isAfter(now)) {
            // 재시도 간격은 30초, 60초, 120초로 늘린다.
            long delaySeconds = RETRY_DELAYS_SECONDS[retryCount.intValue()];
            LocalDateTime retryAt = now.plusSeconds(delaySeconds);

            if (retryAt.isBefore(expiresAt)) {
                nextRetryAt = retryAt;
                return; // 재시도할 동안은 암호문을 보관한다.
            }
        }

        // 재시도 불가, 횟수 소진, 초대 만료 시에는 발송용 암호문을 정리한다.
        encryptedToken = null;
    }

    private void ensureProcessing() {
        if (emailSendStatus != EmailSendStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 발송 작업만 결과를 기록할 수 있습니다.");
        }
    }


}
