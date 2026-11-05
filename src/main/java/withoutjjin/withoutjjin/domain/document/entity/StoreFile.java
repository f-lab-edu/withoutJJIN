package withoutjjin.withoutjjin.domain.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;

// 파일 정보만 보관한다. 이 행의 존재가 S3 업로드 성공을 의미하지는 않는다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "store_file", uniqueConstraints = @UniqueConstraint(
        name = "uk_store_file_object_key", columnNames = "object_key"
))
public class StoreFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, updatable = false)
    private Workspace workspace;

    // 서비스에서 생성하는 S3 키의 길이를 제한하고, 같은 키의 중복 등록을 막는다.
    @Column(name = "object_key", nullable = false, updatable = false, length = 512)
    private String objectKey;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "media_type", nullable = false, updatable = false, length = 255)
    private String mediaType;

    // 업로드 전에는 요청한 크기이며, 완료 처리에서 S3의 실제 크기와 비교한다.
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StoreFile(Workspace workspace, String objectKey, String fileName,
                     String mediaType, Long sizeBytes) {
        if (workspace == null) {
            throw new IllegalArgumentException("파일의 워크스페이스가 필요합니다.");
        }
        validateText(objectKey, 512, "파일 저장 키");
        validateText(fileName, 255, "파일 이름");
        validateText(mediaType, 255, "파일 형식");
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new IllegalArgumentException("파일 크기는 0보다 커야 합니다.");
        }
        this.workspace = workspace;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
    }

    private static void validateText(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + "은 비어 있을 수 없으며 " + maxLength + "자 이하여야 합니다.");
        }
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
