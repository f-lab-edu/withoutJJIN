package withoutjjin.withoutjjin.domain.invitation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@Transactional
class InvitationRepositoryTest {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private InvitationTokenCipher tokenCipher;

    @Test
    void 초대를_저장하고_관계와_초기값을_조회한다() {
        // 1. 초대자 회원 저장

        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        AppUser user = appUserRepository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));

        // 2. 워크스페이스 저장
         Workspace workspace = workspaceRepository.save(new Workspace("hello!?", user));

// 저장 테스트에 사용할 값
        String targetEmail = "invitee@example.com";
        String tokenHash = "test-token-hash-" + UUID.randomUUID();
        String encryptedToken = tokenCipher.encrypt("repository-test-token");

        LocalDateTime expiresAt =
                LocalDateTime.now(ZoneId.of("Asia/Seoul"))
                        .plusDays(1)
                        .withNano(0); // DB 저장 후 정확히 비교하도록 소수 초 제거

// 초대 생성·저장
        Invitation invitation = invitationRepository.saveAndFlush(
                new Invitation(
                        workspace,
                        user,
                        targetEmail,
                        tokenHash,
                        encryptedToken,
                        expiresAt
                )
        );

// ID를 보관하고 기존 관리 객체 분리
        Long invitationId = invitation.getId();
        Long workspaceId = workspace.getId();
        Long userId = user.getId();

        em.clear();

// DB에서 다시 조회
        Invitation found = invitationRepository.findById(invitationId)
                .orElseThrow();

// 초대 정보와 관계 검증
        assertThat(found.getTargetEmail()).isEqualTo(targetEmail);
        assertThat(found.getTokenHash()).isEqualTo(tokenHash);
        assertThat(found.getEncryptedToken()).isEqualTo(encryptedToken);
        assertThat(tokenCipher.decrypt(found.getEncryptedToken())).isEqualTo("repository-test-token");
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getWorkspace().getId()).isEqualTo(workspaceId);
        assertThat(found.getInvitedBy().getId()).isEqualTo(userId);

// 초기 상태 검증
        assertThat(found.getInvitationStatus())
                .isEqualTo(InvitationStatus.PENDING);
        assertThat(found.getEmailSendStatus())
                .isEqualTo(EmailSendStatus.PENDING);
        assertThat(found.getRetryCount()).isEqualTo(0L);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getNextRetryAt()).isNull();
        assertThat(found.getProcessingStartedAt()).isNull();


    }
}
