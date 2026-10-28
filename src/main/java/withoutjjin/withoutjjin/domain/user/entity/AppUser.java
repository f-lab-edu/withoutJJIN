package withoutjjin.withoutjjin.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA가 엔티티를 복원할 때 사용하는 기본 생성자
// DB에서 저장된 회원 데이터를 가져올때 사용하는 기본생성자
@Table(name = "app_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_app_user_issuer_subject", // uk: Unique Key 제약 이름 관례
                columnNames = {"issuer", "subject"}
        )
)
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issuer", nullable = false)
    private String issuer; // 예: https://accounts.google.com

    @Column(name = "subject", nullable = false)
    private String subject; // Google ID 토큰의 sub: 계정 고유 식별자, 구글 부여하는것

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;





    // Google 인증 결과를 검증한 뒤 회원 생성에 사용한다.
    public AppUser(String issuer, String subject, String email,
                   boolean emailVerified, String displayName) {
        this.issuer = issuer;
        this.subject = subject;
        this.email = email;
        this.emailVerified = emailVerified;
        this.displayName = displayName;
    }

    @PrePersist //  insert하기 직전에
    private void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate  // 더티체킹으로 변경 감지하여 Hibernate가 update 수행하기전에 메서드 실행
    private void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

}
