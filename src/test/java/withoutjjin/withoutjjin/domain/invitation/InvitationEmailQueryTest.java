package withoutjjin.withoutjjin.domain.invitation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.EmailSendStatus;
import withoutjjin.withoutjjin.domain.invitation.entity.Invitation;
import withoutjjin.withoutjjin.domain.invitation.entity.InvitationStatus;
import withoutjjin.withoutjjin.domain.invitation.repository.InvitationRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InvitationEmailQueryTest {

    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private EntityManager entityManager;

    private Workspace workspace;
    private AppUser inviter;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString();
        inviter = appUserRepository.save(new AppUser(
                "https://accounts.google.com", uniqueId, uniqueId + "@example.com",
                true, "발송 조회 테스트 회원"
        ));
        workspace = workspaceRepository.save(new Workspace("발송 조회 테스트 팀", inviter));
        now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
    }

    @Test
    void 유효한_초대의_첫_발송을_조회한다() {
        Invitation pending = saveInvitation(now.plusDays(1));
        Long expectedId = pending.getId();
        flushAndClear();

        assertThat(findSendableIds(Pageable.unpaged())).contains(expectedId);
    }

    @Test
    void 재시도_시각이_지났거나_현재와_같은_실패만_조회한다() {
        Invitation past = saveFailedInvitation(now.minusSeconds(31));
        Invitation dueNow = saveFailedInvitation(now.minusSeconds(30));
        Invitation future = saveFailedInvitation(now);
        Long pastId = past.getId();
        Long dueNowId = dueNow.getId();
        Long futureId = future.getId();
        flushAndClear();

        assertThat(findSendableIds(Pageable.unpaged()))
                .contains(pastId, dueNowId)
                .doesNotContain(futureId);
    }

    @Test
    void 만료_수락_취소된_초대는_발송하지_않는다() {
        Invitation expired = saveInvitation(now.minusSeconds(1));
        Invitation expiresNow = saveInvitation(now);
        Invitation accepted = saveInvitation(now.plusDays(1));
        Invitation canceled = saveInvitation(now.plusDays(1));

        // 수락·취소 API는 아직 없으므로 조회 검증용 DB 상태를 직접 준비한다.
        changeInvitationStatus(accepted.getId(), InvitationStatus.ACCEPTED);
        changeInvitationStatus(canceled.getId(), InvitationStatus.CANCELED);
        List<Long> excludedIds = List.of(expired.getId(), expiresNow.getId(), accepted.getId(), canceled.getId());
        flushAndClear();

        assertThat(findSendableIds(Pageable.unpaged())).doesNotContainAnyElementsOf(excludedIds);
    }

    @Test
    void 처리중이거나_이미_접수된_발송은_조회하지_않는다() {
        Invitation processing = saveInvitation(now.plusDays(1));
        processing.startProcessing(now);
        Invitation submitted = saveInvitation(now.plusDays(1));
        submitted.startProcessing(now);
        submitted.markSubmitted();
        Long processingId = processing.getId();
        Long submittedId = submitted.getId();
        flushAndClear();

        assertThat(findSendableIds(Pageable.unpaged())).doesNotContain(processingId, submittedId);
    }

    @Test
    void 최종_실패이거나_암호문이_없는_초대는_조회하지_않는다() {
        Invitation failed = saveInvitation(now.plusDays(1));
        failed.startProcessing(now);
        failed.markFailed(now, false);
        Invitation legacy = saveInvitation(now.plusDays(1));

        // 암호문 필드를 추가하기 전에 저장된 기존 행을 재현한다.
        entityManager.createQuery("update Invitation i set i.encryptedToken = null where i.id = :id")
                .setParameter("id", legacy.getId()).executeUpdate();
        Long failedId = failed.getId();
        Long legacyId = legacy.getId();
        flushAndClear();

        assertThat(findSendableIds(Pageable.unpaged())).doesNotContain(failedId, legacyId);
    }

    @Test
    void 한번에_조회할_개수를_제한하고_ID순으로_조회한다() {
        saveInvitation(now.plusDays(1));
        saveInvitation(now.plusDays(1));
        saveInvitation(now.plusDays(1));
        flushAndClear();

        // 기존 개발 데이터가 있어도 삭제하지 않고, 전체 후보의 앞 두 건과 비교한다.
        List<Long> allCandidates = findSendableIds(Pageable.unpaged());
        List<Long> firstBatch = findSendableIds(PageRequest.of(0, 2));

        assertThat(firstBatch).hasSize(2).isSorted();
        assertThat(firstBatch).containsExactlyElementsOf(allCandidates.subList(0, 2));
    }

    @Test
    void 수신_주소를_제한하면_해당_주소의_발송과_복구_대상만_조회한다() {
        Invitation allowed = saveInvitation(now.plusDays(1));
        Invitation excluded = saveInvitation(now.plusDays(1));
        String recipient = allowed.getTargetEmail();
        flushAndClear();

        assertThat(invitationRepository.findSendableIds(
                InvitationStatus.PENDING, EmailSendStatus.PENDING, EmailSendStatus.FAILED,
                now, recipient, Pageable.unpaged())).containsExactly(allowed.getId());

        Invitation allowedProcessing = invitationRepository.findById(allowed.getId()).orElseThrow();
        Invitation excludedProcessing = invitationRepository.findById(excluded.getId()).orElseThrow();
        allowedProcessing.startProcessing(now.minusMinutes(6));
        excludedProcessing.startProcessing(now.minusMinutes(6));
        flushAndClear();

        assertThat(invitationRepository.findTimedOutProcessingIds(
                EmailSendStatus.PROCESSING, now.minusMinutes(5), recipient, Pageable.unpaged()))
                .containsExactly(allowed.getId());
    }

    private Invitation saveInvitation(LocalDateTime expiresAt) {
        return invitationRepository.save(new Invitation(
                workspace, inviter, UUID.randomUUID() + "@example.com",
                "test-hash-" + UUID.randomUUID(), "test-ciphertext", expiresAt
        ));
    }

    private Invitation saveFailedInvitation(LocalDateTime failedAt) {
        Invitation invitation = saveInvitation(now.plusDays(1));
        invitation.startProcessing(failedAt);
        invitation.markFailed(failedAt, true);
        return invitation;
    }

    private void changeInvitationStatus(Long id, InvitationStatus status) {
        entityManager.createQuery("update Invitation i set i.invitationStatus = :status where i.id = :id")
                .setParameter("status", status).setParameter("id", id).executeUpdate();
    }

    private void flushAndClear() {
        invitationRepository.flush();
        entityManager.clear();
    }

    private List<Long> findSendableIds(Pageable pageable) {
        return invitationRepository.findSendableIds(
                InvitationStatus.PENDING, EmailSendStatus.PENDING, EmailSendStatus.FAILED,
                now, pageable
        );
    }
}
