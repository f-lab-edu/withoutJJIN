package withoutjjin.withoutjjin.domain.invitation.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.mail.InvitationEmailSender;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenCipher;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.invitation.worker.InvitationMailTransactionService.MailTask;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 테스트 전체 트랜잭션을 사용하지 않아, 실제 서비스의 커밋과 잠금 범위를 확인한다.
@SpringBootTest(properties = "invitation.mail.page-url=https://app.example.com/invite")
@ActiveProfiles("test")
class InvitationEmailWorkerIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

    @Autowired private InvitationEmailWorker worker;
    @Autowired private InvitationMailTransactionService transactions;
    @Autowired private InvitationRepository invitationRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private InvitationTokenCipher tokenCipher;
    @Autowired private InvitationTokenGenerator tokenGenerator;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private InvitationEmailSender emailSender;
    @MockitoBean(name = "invitationMailClock") private Clock clock;

    private AtomicReference<Instant> currentTime;
    private AppUser inviter;
    private Workspace workspace;
    private String token;

    @BeforeEach
    void setUp() {
        currentTime = new AtomicReference<>(NOW.atZone(ZONE).toInstant());
        when(clock.instant()).thenAnswer(invocation -> currentTime.get());
        when(clock.getZone()).thenReturn(ZONE);
        String uniqueId = UUID.randomUUID().toString();
        inviter = appUserRepository.saveAndFlush(new AppUser(
                "https://accounts.google.com", uniqueId, uniqueId + "@example.com", true, "작업자 테스트 회원"
        ));
        workspace = workspaceRepository.saveAndFlush(new Workspace("작업자 테스트 팀", inviter));
        token = tokenGenerator.generateToken();
    }

    @AfterEach
    void cleanUp() {
        // 이 테스트가 커밋한 데이터만 FK 순서에 맞춰 정리한다.
        if (workspace != null) {
            jdbcTemplate.update("delete from invitation where workspace_id = ?", workspace.getId());
            jdbcTemplate.update("delete from workspace where id = ?", workspace.getId());
        }
        if (inviter != null) {
            jdbcTemplate.update("delete from app_user where id = ?", inviter.getId());
        }
    }

    @Test
    @DisplayName("처리 시작을 커밋한 뒤 트랜잭션 밖에서 발송하고 접수 성공을 기록한다")
    void commitsClaimBeforeSendingOutsideTransactionAndRecordsSubmission() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
            return "test-ses-message-id";
        }).when(emailSender).sendInvitation(anyString(), anyString(), anyString());

        worker.processInvitation(invitation.getId());

        verify(emailSender).sendInvitation(invitation.getTargetEmail(), workspace.getName(),
                "https://app.example.com/invite?token=" + token);
        Invitation result = read(invitation.getId());
        assertThat(result.getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        assertThat(result.getEncryptedToken()).isNull();
        assertThat(result.getTokenHash()).isEqualTo(tokenGenerator.hashToken(token));
        assertThat(result.getProcessingStartedAt()).isNull();
    }

    @Test
    @DisplayName("일시적 실패 후 재시도 시각이 되면 다시 발송한다")
    void retriesTemporaryFailureWhenRetryTimeArrives() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        when(emailSender.sendInvitation(anyString(), anyString(), anyString()))
                .thenThrow(TooManyRequestsException.builder().statusCode(429).message("test throttling").build())
                .thenReturn("test-message-id");

        worker.processInvitation(invitation.getId());
        Invitation failed = read(invitation.getId());
        assertThat(failed.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(failed.getNextRetryAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(failed.getEncryptedToken()).isNotNull();

        worker.processInvitation(invitation.getId()); // 아직 재시도 시각 전이므로 건너뛴다.
        verify(emailSender, times(1)).sendInvitation(anyString(), anyString(), anyString());

        setTime(NOW.plusSeconds(30));
        worker.processInvitation(invitation.getId());

        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        assertThat(read(invitation.getId()).getRetryCount()).isEqualTo(1L);
        verify(emailSender, times(2)).sendInvitation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("SES 영구 거부는 재시도하지 않는다")
    void doesNotRetryPermanentSesRejection() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        when(emailSender.sendInvitation(anyString(), anyString(), anyString()))
                .thenThrow(MessageRejectedException.builder().statusCode(400).message("test rejection").build());

        worker.processInvitation(invitation.getId());
        worker.processInvitation(invitation.getId());

        Invitation result = read(invitation.getId());
        assertThat(result.getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(result.getNextRetryAt()).isNull();
        assertThat(result.getEncryptedToken()).isNull();
        verify(emailSender, times(1)).sendInvitation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("SDK 통신 오류는 제한된 재시도를 예약한다")
    void schedulesLimitedRetriesForSdkCommunicationFailure() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        when(emailSender.sendInvitation(anyString(), anyString(), anyString()))
                .thenThrow(SdkClientException.create("test connection failure"));

        worker.processInvitation(invitation.getId());

        Invitation result = read(invitation.getId());
        assertThat(result.getNextRetryAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(result.getEncryptedToken()).isNotNull();
    }

    @Test
    @DisplayName("복호화할 수 없는 토큰은 발송하지 않는다")
    void doesNotSendUndecryptableToken() {
        Invitation invitation = invitationRepository.saveAndFlush(new Invitation(
                workspace, inviter, "invitee@example.com", "bad-token-hash", "v1:invalid", NOW.plusDays(1)
        ));

        worker.processInvitation(invitation.getId());

        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(read(invitation.getId()).getNextRetryAt()).isNull();
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("원문과 해시가 맞지 않으면 발송하지 않는다")
    void doesNotSendTokenWhoseHashDoesNotMatch() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        jdbcTemplate.update("update invitation set token_hash = ? where id = ?",
                "mismatch-" + UUID.randomUUID(), invitation.getId());

        worker.processInvitation(invitation.getId());

        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.FAILED);
        assertThat(read(invitation.getId()).getEncryptedToken()).isNull();
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("만료된 초대는 건너뛴다")
    void skipsExpiredInvitations() {
        Invitation invitation = saveInvitation(NOW);

        worker.processInvitation(invitation.getId());

        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("같은 InvitationId로 processInvitation을 동시에 호출해도 메일은 한번만 발송한다")
    void sendsOnceWhenProcessingSameInvitationConcurrently() throws Exception {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return "test-message-id";
        }).when(emailSender).sendInvitation(anyString(), anyString(), anyString());

        try {
            Future<?> first = executor.submit(() -> worker.processInvitation(invitation.getId()));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            // 첫 작업자가 메일 응답을 기다리는 동안 두 번째 작업자는 처리 중 상태를 보고 종료한다.
            executor.submit(() -> worker.processInvitation(invitation.getId())).get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            verify(emailSender, times(1)).sendInvitation(anyString(), anyString(), anyString());
            assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("recoverTimedOut 후 processingStartedAt이 일치하지 않는 MailTask의 결과는 반영하지 않는다")
    void ignoresStaleTaskResultAfterTimedOutProcessingIsRecovered() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));
        MailTask oldTask = transactions.claim(invitation.getId(), NOW).orElseThrow();
        LocalDateTime recoveredAt = NOW.plusMinutes(6);

        transactions.recoverTimedOut(invitation.getId(), recoveredAt.minusMinutes(5), recoveredAt);
        assertThat(read(invitation.getId()).getNextRetryAt()).isEqualTo(recoveredAt.plusSeconds(30));
        MailTask newTask = transactions.claim(invitation.getId(), recoveredAt.plusSeconds(30)).orElseThrow();

        transactions.markSubmitted(oldTask);
        transactions.markFailed(oldTask, recoveredAt.plusSeconds(30), true);

        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.PROCESSING);
        assertThat(read(invitation.getId()).getProcessingStartedAt()).isEqualTo(newTask.processingStartedAt());
        transactions.markSubmitted(newTask);
        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.SUBMITTED);
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("processingStartedAt이 cutoff 이하인 PROCESSING InvitationId만 조회한다")
    void findsProcessingInvitationIdsStartedAtOrBeforeCutoff() {
        Invitation stale = saveInvitation(NOW.plusDays(1));
        transactions.claim(stale.getId(), NOW.minusMinutes(5));
        token = tokenGenerator.generateToken();
        Invitation fresh = saveInvitation(NOW.plusDays(1));
        transactions.claim(fresh.getId(), NOW.minusMinutes(4));

        assertThat(invitationRepository.findTimedOutProcessingIds(
                EmailSendStatus.PROCESSING, NOW.minusMinutes(5), Pageable.unpaged()
        )).contains(stale.getId()).doesNotContain(fresh.getId());
    }

    @Test
    @DisplayName("트랜잭션이 열려있으면 InvitationEmailWorker의 processInvitation 호출을 거부한다")
    void rejectsProcessingWithinExistingTransaction() {
        Invitation invitation = saveInvitation(NOW.plusDays(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThatThrownBy(() -> worker.processInvitation(invitation.getId()))
                        .isInstanceOf(IllegalTransactionStateException.class));

        verifyNoInteractions(emailSender);
        assertThat(read(invitation.getId()).getEmailSendStatus()).isEqualTo(EmailSendStatus.PENDING);
    }

    private Invitation saveInvitation(LocalDateTime expiresAt) {
        return invitationRepository.saveAndFlush(new Invitation(
                workspace, inviter, "invitee@example.com", tokenGenerator.hashToken(token),
                tokenCipher.encrypt(token), expiresAt
        ));
    }

    private Invitation read(Long id) {
        return invitationRepository.findById(id).orElseThrow();
    }

    private void setTime(LocalDateTime time) {
        currentTime.set(time.atZone(ZONE).toInstant());
    }
}
