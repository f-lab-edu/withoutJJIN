package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

// DocumentVersion과 StoreFile을 삭제한 뒤에도 S3 삭제를 재시도할 수 있도록 키를 보관한다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "s3_object_deletion_task",
        indexes = @Index(name = "idx_s3_deletion_next_attempt_id", columnList = "next_attempt_at, id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_s3_object_deletion_task_object_key", columnNames = "object_key"))
public class S3ObjectDeletionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 최초 실행 시각, 실패 후 재시도 시각 또는 처리 중인 작업의 점유 만료 시각이다.
    // 기존 행의 null은 즉시 처리 가능한 작업으로 취급한다.
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    public S3ObjectDeletionTask(String objectKey) {
        this(objectKey, null);
    }

    public S3ObjectDeletionTask(String objectKey, LocalDateTime nextAttemptAt) {
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 512) {
            throw new IllegalArgumentException("S3 삭제 작업의 저장 키는 비어 있지 않고 512자 이하여야 합니다.");
        }
        this.objectKey = objectKey;
        this.nextAttemptAt = nextAttemptAt;
    }

    public boolean isReady(LocalDateTime now) {
        return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }

    public String claim(LocalDateTime now, Duration claimLease) {
        if (!isReady(now)) {
            throw new IllegalStateException("S3 삭제 작업의 실행 시각이 되지 않았습니다.");
        }
        this.claimToken = UUID.randomUUID().toString();
        this.nextAttemptAt = now.plus(claimLease);
        return claimToken;
    }

    public boolean hasClaim(String token) {
        return token != null && token.equals(claimToken);
    }

    public void scheduleRetry(LocalDateTime now, Duration retryDelay) {
        this.claimToken = null;
        this.nextAttemptAt = now.plus(retryDelay);
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
