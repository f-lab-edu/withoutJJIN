package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadVerifier;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Service의 실제 커밋·롤백을 검증하므로 테스트 전체를 트랜잭션으로 감싸지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadCompletionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private DocumentUploadCompletionService service;
    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private StoreFileRepository files;
    @Autowired private DocumentUploadRepository uploads;
    @Autowired private DocumentRepository documents;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private S3UploadVerifier verifier;
    @MockitoBean(name = "documentUploadClock") private Clock clock;
    @MockitoSpyBean private DocumentVersionRepository versions;

    private AppUser requester;
    private AppUser otherUser;
    private Workspace workspace;
    private StoreFile originalFile;
    private DocumentUpload upload;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZONE);
        requester = users.saveAndFlush(user("요청자"));
        otherUser = users.saveAndFlush(user("다른 회원"));
        workspace = workspaces.saveAndFlush(new Workspace("완료 Service 테스트팀", requester));
        members.saveAndFlush(new WorkspaceMember(workspace, requester));
        originalFile = files.saveAndFlush(file());
        upload = uploads.saveAndFlush(new DocumentUpload(
                workspace, null, requester, originalFile, 1024L,
                LocalDateTime.ofInstant(NOW.plusSeconds(300), ZONE)));
    }

    @Test
    @DisplayName("완료 API가 실제 Service와 DB에 Document·DocumentVersion을 등록하고 ID들을 응답한다")
    void registersDocumentThroughCompletionApi() throws Exception {
        var response = mockMvc.perform(post(
                        "/api/v1/uploads/{uploadId}/complete", upload.getId())
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", requester.getIssuer())
                                .subject(requester.getSubject())))
                        .with(csrf().asHeader()))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").value(upload.getId()));

        Long documentId = jdbc.queryForObject(
                "SELECT document_id FROM document_upload WHERE id = ?", Long.class, upload.getId());
        Long versionId = jdbc.queryForObject(
                "SELECT id FROM document_version WHERE document_id = ? AND version_number = 1",
                Long.class, documentId);
        response.andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.documentVersionId").value(versionId));
        assertThat(countDocuments()).isEqualTo(1);
        assertThat(countVersions()).isEqualTo(1);
        assertThat(status()).isEqualTo("COMPLETED");
        verify(verifier).verify(originalFile.getObjectKey(), 1024L, MEDIA_TYPE);
    }

    @Test
    @DisplayName("S3 검증은 트랜잭션 밖에서 실행하고 Document·DocumentVersion·COMPLETED를 함께 커밋한다")
    void commitsDocumentVersionAndCompletionTogether() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(verifier).verify(originalFile.getObjectKey(), 1024L, MEDIA_TYPE);

        CompleteUploadResponse response = complete();

        assertThat(response.uploadId()).isEqualTo(upload.getId());
        assertThat(response.documentId()).isPositive();
        assertThat(response.documentVersionId()).isPositive();
        var document = documents.findById(response.documentId()).orElseThrow();
        DocumentVersion version = versions.findById(response.documentVersionId()).orElseThrow();
        assertThat(document.getTitle()).isEqualTo("기획서.docx");
        assertThat(document.getRevision()).isEqualTo(1);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getStorageKey()).isEqualTo(originalFile.getObjectKey());
        assertThat(version.getFileSize()).isEqualTo(1024L);
        assertThat(version.getPageCount()).isNull();
        assertThat(version.getUploadedBy().getId()).isEqualTo(requester.getId());
        assertThat(countDocuments()).isEqualTo(1);
        assertThat(countVersions()).isEqualTo(1);
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT document_id FROM document_upload WHERE id = ?",
                Long.class, upload.getId())).isEqualTo(response.documentId());
    }

    @Test
    @DisplayName("같은 uploadId를 재요청하면 만료 후에도 같은 결과를 반환하고 S3를 다시 검증하지 않는다")
    void returnsSameResultOnRepeatedCompletionEvenAfterExpiry() {
        CompleteUploadResponse first = complete();
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));

        assertThat(complete()).isEqualTo(first);

        assertThat(countDocuments()).isEqualTo(1);
        assertThat(countVersions()).isEqualTo(1);
        verify(verifier, times(1)).verify(originalFile.getObjectKey(), 1024L, MEDIA_TYPE);
    }

    @Test
    @DisplayName("다른 AppUser의 완료 요청은 거부한다")
    void rejectsAnotherRequester() {
        assertThatThrownBy(() -> service.completeUpload(
                upload.getId(), otherUser.getIssuer(), otherUser.getSubject()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
        assertUnregistered();
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("WorkspaceMember에서 탈퇴한 요청자의 완료 요청은 거부한다")
    void rejectsRequesterWhoLeftWorkspace() {
        removeMembership();
        assertRejected(403);
        verifyNoInteractions(verifier);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("uploadId가 없거나 양수가 아니면 거부한다")
    void rejectsInvalidUploadId(Long uploadId) {
        assertThatThrownBy(() -> service.completeUpload(
                uploadId, requester.getIssuer(), requester.getSubject()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
        assertUnregistered();
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("존재하지 않는 DocumentUpload이면 거부한다")
    void rejectsMissingUpload() {
        assertThatThrownBy(() -> service.completeUpload(
                Long.MAX_VALUE, requester.getIssuer(), requester.getSubject()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertUnregistered();
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("expiresAt과 현재 시각이 같으면 만료된 DocumentUpload로 거부한다")
    void rejectsUploadAtExpiryBoundary() {
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertRejected(410);
        verifyNoInteractions(verifier);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentUploadStatus.class, names = {"FAILED", "EXPIRED"})
    @DisplayName("FAILED·EXPIRED 상태의 완료 요청은 거부한다")
    void rejectsNonPendingUpload(DocumentUploadStatus status) {
        jdbc.update("UPDATE document_upload SET status = ? WHERE id = ?", status.name(), upload.getId());
        assertRejected(409);
        assertThat(status()).isEqualTo(status.name());
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("originalFile이 없으면 Document를 등록하지 않는다")
    void rejectsMissingOriginalFile() {
        jdbc.update("UPDATE document_upload SET original_file_id = NULL WHERE id = ?", upload.getId());
        assertRejected(409);
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("S3 검증이 실패하면 Document·DocumentVersion을 등록하지 않는다")
    void leavesUploadPendingWhenS3VerificationFails() {
        doThrow(new IllegalStateException("StoreFile.sizeBytes 불일치"))
                .when(verifier).verify(anyString(), anyLong(), anyString());

        assertRejected(409);

        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("S3 검증 중 탈퇴하면 최종 트랜잭션에서 권한을 다시 확인한다")
    void rechecksMembershipAfterS3Verification() {
        doAnswer(invocation -> {
            removeMembership();
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());

        assertRejected(403);

        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("S3 검증 중 만료되면 최종 트랜잭션에서 만료를 다시 확인한다")
    void rechecksExpiryAfterS3Verification() {
        doAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(300));
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());

        assertRejected(410);

        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("S3 검증 후 originalFile이 교체되면 검증하지 않은 StoreFile을 등록하지 않는다")
    void rejectsOriginalFileChangedDuringVerification() {
        doAnswer(invocation -> {
            StoreFile replacement = files.saveAndFlush(file());
            jdbc.update("UPDATE document_upload SET original_file_id = ? WHERE id = ?",
                    replacement.getId(), upload.getId());
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());

        assertRejected(409);

        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("DocumentVersion 저장 실패 시 먼저 INSERT한 Document도 롤백한다")
    void rollsBackDocumentWhenVersionSaveFails() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("테스트용 DocumentVersion 저장 실패");
        doAnswer(invocation -> {
            documents.flush();
            assertThat(countDocuments()).isEqualTo(1);
            throw failure;
        }).when(versions).save(any(DocumentVersion.class));

        assertThatThrownBy(this::complete).isSameAs(failure);

        assertUnregistered();
        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("동시에 같은 uploadId를 완료해도 Document·DocumentVersion은 한 번만 생성한다")
    void registersOnlyOnceForConcurrentCompletion() throws Exception {
        CyclicBarrier bothVerified = new CyclicBarrier(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            bothVerified.await(15, TimeUnit.SECONDS);
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(this::complete);
            var second = executor.submit(this::complete);
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(countDocuments()).isEqualTo(1);
            assertThat(countVersions()).isEqualTo(1);
            assertThat(status()).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private CompleteUploadResponse complete() {
        return service.completeUpload(upload.getId(), requester.getIssuer(), requester.getSubject());
    }

    private void assertRejected(int expectedStatus) {
        assertThatThrownBy(this::complete)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expectedStatus));
        assertUnregistered();
    }

    private void assertUnregistered() {
        assertThat(countDocuments()).isZero();
        assertThat(countVersions()).isZero();
    }

    private void removeMembership() {
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(), requester.getId());
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM document_upload WHERE id = ?",
                String.class, upload.getId());
    }

    private long countDocuments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE workspace_id = ?",
                Long.class, workspace.getId());
    }

    private long countVersions() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_version v JOIN document d ON v.document_id = d.id"
                        + " WHERE d.workspace_id = ?", Long.class, workspace.getId());
    }

    private AppUser user(String name) {
        return new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "completion-test@example.com", true, name);
    }

    private StoreFile file() {
        return new StoreFile(workspace,
                "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", MEDIA_TYPE, 1024L);
    }

    @AfterEach
    void cleanUpOnlyThisTestsData() {
        if (workspace != null) {
            jdbc.update("DELETE FROM document_upload WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE v FROM document_version v JOIN document d ON v.document_id = d.id"
                    + " WHERE d.workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM document WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM store_file WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (requester != null) users.deleteById(requester.getId());
        if (otherUser != null) users.deleteById(otherUser.getId());
    }
}
