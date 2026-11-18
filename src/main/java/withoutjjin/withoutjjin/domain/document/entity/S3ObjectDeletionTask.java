package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

// DocumentVersion과 StoreFile을 삭제한 뒤에도 S3 삭제를 재시도할 수 있도록 키를 보관한다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "s3_object_deletion_task", uniqueConstraints = @UniqueConstraint(
        name = "uk_s3_object_deletion_task_object_key", columnNames = "object_key"
))
public class S3ObjectDeletionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public S3ObjectDeletionTask(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 512) {
            throw new IllegalArgumentException("S3 삭제 작업의 저장 키는 비어 있지 않고 512자 이하여야 합니다.");
        }
        this.objectKey = objectKey;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
