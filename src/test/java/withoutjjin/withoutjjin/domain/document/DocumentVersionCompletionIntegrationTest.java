package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.entity.*;
import withoutjjin.withoutjjin.domain.document.repository.*;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionTransactions;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadVerifier;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
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
import java.util.List;
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

// 별도 스레드의 실제 커밋·잠금·롤백을 확인하므로 테스트 메서드에 @Transactional을 붙이지 않는다.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentVersionCompletionIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private DocumentUploadCompletionService service;
    @Autowired private DocumentUploadCompletionTransactions transactions;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentUploadRepository uploads;
    @Autowired private StoreFileRepository files;
    @Autowired private DocumentFeedbackRepository feedbacks;
    @MockitoSpyBean private DocumentVersionRepository versions;
    @MockitoSpyBean private DocumentConversionJobRepository jobs;
    @MockitoBean private S3UploadVerifier verifier;
    @MockitoBean(name = "documentUploadClock") private Clock clock;

    private AppUser member, colleague;
    private Workspace workspace;
    private Document document;
    private DocumentVersion v1;
    private DocumentUpload firstUpload, upload;
    private Long firstFeedbackId, firstPdfId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZONE);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            member = users.save(user("문서 작성자"));
            colleague = users.save(user("동료"));
            workspace = workspaces.save(new Workspace("새 버전 완료 테스트 팀", member));
            members.save(new WorkspaceMember(workspace, member));
            members.save(new WorkspaceMember(workspace, colleague));
            document = documents.save(new Document("보존할 문서 제목", 1, member, null, workspace));
            StoreFile original = files.save(file("original-v1.docx", DOCX));
            firstUpload = uploads.save(new DocumentUpload(workspace, document, member, original, 1024L, expiresAt()));
            firstUpload.complete(document);
            StoreFile pdf = files.save(file("review-v1.pdf", "application/pdf"));
            firstUpload.attachReviewPdf(pdf);
            firstPdfId = pdf.getId();
            v1 = versions.save(new DocumentVersion(1, "첫 버전", 2, document, original.getObjectKey(),
                    original.getFileName(), 1024L, member));
            DocumentConversionJob job = jobs.save(new DocumentConversionJob(firstUpload, v1));
            job.startProcessing();
            job.complete();
            firstFeedbackId = feedbacks.save(new DocumentFeedback(v1, member, 1,
                    .1, .2, .3, .4, FeedbackColor.GREEN, "V1에 남긴 의견")).getId();
        });
        upload = pendingUpload(member);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());
    }

    @Test
    void completionApiRegistersV2ForTheSameDocumentAndPreservesV1FilesAndFeedback() throws Exception {
        mvc.perform(post("/api/v1/uploads/{uploadId}/complete", upload.getId())
                        .with(oidcLogin().idToken(token -> token.claim("iss", member.getIssuer()).subject(member.getSubject())))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").value(upload.getId()))
                .andExpect(jsonPath("$.documentId").value(document.getId()))
                .andExpect(jsonPath("$.documentVersionId").isNumber());
        assertThat(revision()).isEqualTo(2);
        assertThat(versionNumbers()).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE workspace_id = ?", Long.class, workspace.getId())).isEqualTo(1);
        assertThat(documents.findById(document.getId()).orElseThrow().getTitle()).isEqualTo("보존할 문서 제목");
        DocumentVersion v2 = versions.findByDocumentIdAndVersionNumber(document.getId(), 2).orElseThrow();
        assertThat(v2.getOriginalFileName()).isEqualTo("수정 이력서.docx");
        assertThat(v2.getPageCount()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM document_conversion_job WHERE document_version_id = ?", String.class, v2.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT document_version_id FROM document_feedback WHERE id = ?", Long.class, firstFeedbackId)).isEqualTo(v1.getId());
        assertThat(jdbc.queryForObject("SELECT content FROM document_feedback WHERE id = ?", String.class, firstFeedbackId)).isEqualTo("V1에 남긴 의견");
        assertThat(jdbc.queryForObject("SELECT review_pdf_file_id FROM document_upload WHERE id = ?", Long.class, firstUpload.getId())).isEqualTo(firstPdfId);
        assertThat(versions.findById(v1.getId()).orElseThrow().getPageCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_feedback WHERE document_version_id = ?", Long.class, v2.getId())).isZero();
    }

    @Test
    void retryingV2AfterV3ReturnsV2AndDoesNotReverifyS3EvenAfterExpiry() {
        CompleteUploadResponse second = complete(upload);
        DocumentUpload next = pendingUpload(colleague);
        CompleteUploadResponse third = service.completeUpload(next.getId(), colleague.getIssuer(), colleague.getSubject());
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        assertThat(complete(upload)).isEqualTo(second);
        assertThat(service.completeUpload(next.getId(), colleague.getIssuer(), colleague.getSubject())).isEqualTo(third);
        assertThat(service.completeUpload(firstUpload.getId(), member.getIssuer(), member.getSubject()).documentVersionId()).isEqualTo(v1.getId());
        assertThat(revision()).isEqualTo(3);
        assertThat(versionNumbers()).containsExactly(1, 2, 3);
        verify(verifier, times(2)).verify(anyString(), anyLong(), anyString());
        assertThat(versions.findById(third.documentVersionId()).orElseThrow().getUploadedBy().getId()).isEqualTo(colleague.getId());
    }

    @Test
    void concurrentDifferentUploadsReceiveV2AndV3WithoutDuplicateNumbers() throws Exception {
        DocumentUpload next = pendingUpload(member);
        barrierAfterVerification();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> complete(upload));
            var b = executor.submit(() -> complete(next));
            var first = a.get(30, TimeUnit.SECONDS);
            var second = b.get(30, TimeUnit.SECONDS);
            assertThat(first.documentId()).isEqualTo(document.getId());
            assertThat(second.documentId()).isEqualTo(document.getId());
            assertThat(first.documentVersionId()).isNotEqualTo(second.documentVersionId());
            assertThat(versionNumbers()).containsExactly(1, 2, 3);
            assertThat(revision()).isEqualTo(3);
            assertThat(jobs.findByDocumentUploadId(upload.getId()).orElseThrow().getDocumentVersion().getId()).isEqualTo(first.documentVersionId());
            assertThat(jobs.findByDocumentUploadId(next.getId()).orElseThrow().getDocumentVersion().getId()).isEqualTo(second.documentVersionId());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentCompletionOfTheSameUploadRegistersOnlyOneV2() throws Exception {
        barrierAfterVerification();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> complete(upload));
            var b = executor.submit(() -> complete(upload));
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(b.get(30, TimeUnit.SECONDS));
            assertThat(versionNumbers()).containsExactly(1, 2);
            assertThat(revision()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_conversion_job WHERE document_upload_id = ?", Long.class, upload.getId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void refreshesAPreviouslyLoadedDocumentAfterAnotherUploadHasCommitted() {
        var prepared = transactions.prepare(upload.getId(), member.getIssuer(), member.getSubject());
        DocumentUpload next = pendingUpload(member);
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CompleteUploadResponse response = outer.execute(status -> {
            assertThat(documents.findById(document.getId()).orElseThrow().getRevision()).isEqualTo(1);
            complete(next); // NOT_SUPPORTED suspends this transaction; V2 commits independently.
            return transactions.complete(upload.getId(), member.getIssuer(), member.getSubject(), prepared);
        });
        assertThat(versions.findById(response.documentVersionId()).orElseThrow().getVersionNumber()).isEqualTo(3);
        assertThat(versionNumbers()).containsExactly(1, 2, 3);
        assertThat(revision()).isEqualTo(3);
    }

    @Test
    void refreshesAPreviouslyLoadedPendingUploadAfterItHasAlreadyCompleted() {
        var prepared = transactions.prepare(upload.getId(), member.getIssuer(), member.getSubject());
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            assertThat(uploads.findById(upload.getId()).orElseThrow().getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
            CompleteUploadResponse committed = complete(upload);
            assertThat(transactions.complete(upload.getId(), member.getIssuer(), member.getSubject(), prepared)).isEqualTo(committed);
        });
        assertThat(versionNumbers()).containsExactly(1, 2);
        assertThat(revision()).isEqualTo(2);
    }

    @Test
    void rollsBackV2AndRevisionWhenSavingConversionJobFails() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("변환 작업 저장 실패");
        doAnswer(call -> {
            versions.flush();
            assertThat(revision()).isEqualTo(2);
            assertThat(uploadStatus()).isEqualTo("COMPLETED");
            throw failure;
        }).when(jobs).save(any(DocumentConversionJob.class));
        assertThatThrownBy(() -> complete(upload)).isSameAs(failure);
        assertStillPendingWithoutV2();
    }

    @Test
    void rollsBackWhenSavingDocumentVersionFails() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("버전 저장 실패");
        doThrow(failure).when(versions).save(any(DocumentVersion.class));
        assertThatThrownBy(() -> complete(upload)).isSameAs(failure);
        assertStillPendingWithoutV2();
    }

    @Test
    void leavesV1CurrentWhenS3VerificationFails() {
        doThrow(new IllegalStateException("StoreFile.sizeBytes 불일치"))
                .when(verifier).verify(anyString(), anyLong(), anyString());
        assertRejected(409);
        assertStillPendingWithoutV2();
    }

    @Test
    void rejectsAnotherRequesterEvenIfTheyAreAWorkspaceMember() {
        assertThatThrownBy(() -> service.completeUpload(upload.getId(), colleague.getIssuer(), colleague.getSubject()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(verifier);
        assertStillPendingWithoutV2();
    }

    @Test
    void rechecksMembershipAfterS3Verification() {
        doAnswer(call -> {
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), member.getId());
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());
        assertRejected(403);
        assertStillPendingWithoutV2();
    }

    @Test
    void rejectsATargetDocumentChangedDuringS3Verification() {
        Document other = documents.saveAndFlush(new Document("다른 문서", 0, member, null, workspace));
        doAnswer(call -> {
            jdbc.update("UPDATE document_upload SET document_id = ? WHERE id = ?", other.getId(), upload.getId());
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());
        assertRejected(409);
        assertStillPendingWithoutV2();
        assertThat(documents.findById(other.getId()).orElseThrow().getRevision()).isZero();
    }

    @Test
    void rejectsExpiredNewVersionUploadBeforeS3Verification() {
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertRejected(410);
        verifyNoInteractions(verifier);
        assertStillPendingWithoutV2();
    }

    @Test
    void refusesToOverflowVersionNumbers() {
        jdbc.update("UPDATE document SET revision = ? WHERE id = ?", Integer.MAX_VALUE, document.getId());
        assertRejected(409);
        assertThat(revision()).isEqualTo(Integer.MAX_VALUE);
        assertThat(versionNumbers()).containsExactly(1);
        assertThat(uploadStatus()).isEqualTo("PENDING");
    }

    @Test
    void doesNotReturnV1WhenACompletedV2UploadHasLostItsConversionJob() {
        complete(upload);
        jdbc.update("DELETE FROM document_conversion_job WHERE document_upload_id = ?", upload.getId());
        assertThatThrownBy(() -> complete(upload)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DocumentConversionJob");
        assertThat(versionNumbers()).containsExactly(1, 2);
        verify(verifier, times(1)).verify(anyString(), anyLong(), anyString());
    }

    private void barrierAfterVerification() {
        CyclicBarrier bothVerified = new CyclicBarrier(2);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            bothVerified.await(15, TimeUnit.SECONDS);
            return null;
        }).when(verifier).verify(anyString(), anyLong(), anyString());
    }

    private DocumentUpload pendingUpload(AppUser requester) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            StoreFile original = files.save(file("수정 이력서.docx", DOCX));
            return uploads.save(new DocumentUpload(workspace, document, requester, original, 1024L, expiresAt()));
        });
    }

    private CompleteUploadResponse complete(DocumentUpload target) {
        return service.completeUpload(target.getId(), member.getIssuer(), member.getSubject());
    }

    private List<Integer> versionNumbers() {
        return jdbc.queryForList("SELECT version_number FROM document_version WHERE document_id = ? ORDER BY version_number", Integer.class, document.getId());
    }

    private int revision() {
        return jdbc.queryForObject("SELECT revision FROM document WHERE id = ?", Integer.class, document.getId());
    }

    private String uploadStatus() {
        return jdbc.queryForObject("SELECT status FROM document_upload WHERE id = ?", String.class, upload.getId());
    }

    private void assertStillPendingWithoutV2() {
        assertThat(revision()).isEqualTo(1);
        assertThat(versionNumbers()).containsExactly(1);
        assertThat(uploadStatus()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_conversion_job WHERE document_upload_id = ?", Long.class, upload.getId())).isZero();
    }

    private void assertRejected(int status) {
        assertThatThrownBy(() -> complete(upload)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    private LocalDateTime expiresAt() { return LocalDateTime.ofInstant(NOW.plusSeconds(300), ZONE); }

    private StoreFile file(String name, String mediaType) {
        return new StoreFile(workspace, "workspaces/" + workspace.getId() + "/" + UUID.randomUUID(), name, mediaType, 1024L);
    }

    private AppUser user(String name) {
        return new AppUser("https://accounts.google.com", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.com", true, name);
    }

    @AfterEach
    void cleanUpOnlyThisTestsData() {
        if (workspace != null) {
            jdbc.update("DELETE f FROM document_feedback f JOIN document_version v ON f.document_version_id = v.id JOIN document d ON v.document_id = d.id WHERE d.workspace_id = ?", workspace.getId());
            jdbc.update("DELETE j FROM document_conversion_job j JOIN document_upload u ON j.document_upload_id = u.id WHERE u.workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM document_upload WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE v FROM document_version v JOIN document d ON v.document_id = d.id WHERE d.workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM document WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM store_file WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (member != null) users.deleteById(member.getId());
        if (colleague != null) users.deleteById(colleague.getId());
    }
}
