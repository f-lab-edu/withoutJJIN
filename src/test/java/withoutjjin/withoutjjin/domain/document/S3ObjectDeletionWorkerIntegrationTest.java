package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionDeletionService;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionTransactions;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionTransactions.PreparedDeletion;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionWorker;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"document.storage.deletion.claim-lease=2m", "document.storage.deletion.retry-delay=1m"})
@ActiveProfiles("test")
class S3ObjectDeletionWorkerIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private S3ObjectDeletionWorker worker;
    @Autowired private S3ObjectDeletionTransactions transactions;
    @Autowired private S3ObjectDeletionBatchProcessor processor;
    @Autowired private DocumentVersionDeletionService deletionService;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private S3ObjectDeletionTaskRepository tasks;
    @MockitoBean private S3Client s3Client;
    @MockitoBean(name = "documentUploadClock") private Clock clock;

    private final List<Long> taskIds = new ArrayList<>();
    private Long workspaceId;
    private Long userId;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZONE);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return DeleteObjectResponse.builder().build();
        });
    }

    @AfterEach
    void cleanUpOnlyTestFixtures() {
        for (Long id : taskIds) {
            jdbc.update("delete from s3_object_deletion_task where id = ?", id);
        }
        if (workspaceId != null) {
            jdbc.update("delete from s3_object_deletion_task where object_key like ?", "workspaces/" + workspaceId + "/%");
            jdbc.update("delete j from document_conversion_job j join document_upload u on j.document_upload_id = u.id where u.workspace_id = ?", workspaceId);
            jdbc.update("delete from document_upload where workspace_id = ?", workspaceId);
            jdbc.update("delete v from document_version v join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
            jdbc.update("delete from store_file where workspace_id = ?", workspaceId);
            jdbc.update("delete from document where workspace_id = ?", workspaceId);
            jdbc.update("delete from workspace_member where workspace_id = ?", workspaceId);
            jdbc.update("delete from workspace where id = ?", workspaceId);
            jdbc.update("delete from app_user where id = ?", userId);
        }
    }

    @Test
    void deletesOutsideCallingTransactionAndRemovesTaskAfterSuccess() {
        S3ObjectDeletionTask task = task(null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(worker.process(task.getId())).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        assertThat(tasks.existsById(task.getId())).isFalse();
        verify(s3Client).deleteObject(DeleteObjectRequest.builder().bucket("ftfm-test-bucket").key(task.getObjectKey()).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void retainsFailedTaskAndRetriesOnlyAfterDelay(int statusCode) {
        S3ObjectDeletionTask task = task(now());
        S3Exception failure = (S3Exception) S3Exception.builder().statusCode(statusCode).message("S3 삭제 실패").build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(failure).thenReturn(DeleteObjectResponse.builder().build());

        assertThatThrownBy(() -> worker.process(task.getId())).isSameAs(failure);

        S3ObjectDeletionTask retained = tasks.findById(task.getId()).orElseThrow();
        assertThat(retained.getObjectKey()).isEqualTo(task.getObjectKey());
        assertThat(retained.getClaimToken()).isNull();
        assertThat(retained.getNextAttemptAt()).isEqualTo(now().plusMinutes(1));
        assertThat(worker.process(task.getId())).isFalse();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(worker.process(task.getId())).isTrue();
        assertThat(tasks.existsById(task.getId())).isFalse();
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void ignoresMissingAndNotYetDueTasksWithoutCallingS3() {
        S3ObjectDeletionTask task = task(now().plusSeconds(300));
        assertThat(worker.process(Long.MAX_VALUE)).isFalse();
        assertThat(worker.process(task.getId())).isFalse();
        assertThat(processor.processPendingBatch(5)).isZero();
        assertThat(tasks.findById(task.getId()).orElseThrow().getClaimToken()).isNull();
        verifyNoInteractions(s3Client);
    }

    @Test
    void reclaimsAbandonedTaskAfterLeaseExpires() {
        S3ObjectDeletionTask task = task(now());
        PreparedDeletion abandoned = transactions.claim(task.getId()).orElseThrow();
        assertThat(tasks.findById(task.getId()).orElseThrow().getClaimToken()).isEqualTo(abandoned.claimToken());
        assertThat(worker.process(task.getId())).isFalse();

        when(clock.instant()).thenReturn(NOW.plusSeconds(120));

        assertThat(worker.process(task.getId())).isTrue();
        assertThat(tasks.existsById(task.getId())).isFalse();
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void staleExecutionCannotCompleteOrRescheduleReclaimedTask() {
        S3ObjectDeletionTask task = task(now());
        PreparedDeletion first = transactions.claim(task.getId()).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        PreparedDeletion second = transactions.claim(task.getId()).orElseThrow();
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        assertThat(transactions.complete(first)).isFalse();
        transactions.retry(first);

        S3ObjectDeletionTask current = tasks.findById(task.getId()).orElseThrow();
        assertThat(current.getClaimToken()).isEqualTo(second.claimToken());
        assertThat(current.getNextAttemptAt()).isEqualTo(now().plusMinutes(2));
        assertThat(transactions.complete(second)).isTrue();
        assertThat(tasks.existsById(task.getId())).isFalse();
        verifyNoInteractions(s3Client);
    }

    @Test
    @Timeout(20)
    void concurrentExecutionSkipsClaimedTaskWithoutHoldingDatabaseLockDuringS3() throws Exception {
        S3ObjectDeletionTask task = task(now());
        CountDownLatch enteredS3 = new CountDownLatch(1);
        CountDownLatch releaseS3 = new CountDownLatch(1);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            enteredS3.countDown();
            assertThat(releaseS3.await(10, TimeUnit.SECONDS)).isTrue();
            return DeleteObjectResponse.builder().build();
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> worker.process(task.getId()));
            assertThat(enteredS3.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> worker.process(task.getId()));
            assertThat(second.get(3, TimeUnit.SECONDS)).isFalse();
            assertThat(tasks.existsById(task.getId())).isTrue();
            releaseS3.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.existsById(task.getId())).isFalse();
            verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        } finally {
            releaseS3.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedFirstTaskDoesNotStarveLaterTasksEvenWhenBatchSizeIsOne() {
        S3ObjectDeletionTask first = task(now());
        S3ObjectDeletionTask second = task(now());
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            DeleteObjectRequest request = call.getArgument(0);
            if (request.key().equals(first.getObjectKey())) {
                throw S3Exception.builder().statusCode(403).message("S3 접근 거부").build();
            }
            return DeleteObjectResponse.builder().build();
        });

        assertThat(processor.processPendingBatch(1)).isZero();
        assertThat(processor.processPendingBatch(1)).isEqualTo(1);

        assertThat(tasks.existsById(first.getId())).isTrue();
        assertThat(tasks.existsById(second.getId())).isFalse();
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void s3SuccessFollowedByDatabaseFailureKeepsTaskForRetry() {
        S3ObjectDeletionTask task = task(now());
        RuntimeException failure = new IllegalStateException("DB 삭제 실패");
        doThrow(failure).when(tasks).delete(any(S3ObjectDeletionTask.class));

        assertThatThrownBy(() -> worker.process(task.getId())).isSameAs(failure);

        S3ObjectDeletionTask retained = tasks.findById(task.getId()).orElseThrow();
        assertThat(retained.getClaimToken()).isNull();
        assertThat(retained.getNextAttemptAt()).isEqualTo(now().plusMinutes(1));
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void newlyDueTaskIsNotOvertakenByEarlierTaskWhoseRetryTimeIsLater() {
        S3ObjectDeletionTask failed = task(now());
        S3ObjectDeletionTask next = task(now().plusSeconds(30));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            if (request.key().equals(failed.getObjectKey())) {
                throw S3Exception.builder().statusCode(403).message("계속 실패하는 작업").build();
            }
            return DeleteObjectResponse.builder().build();
        });

        assertThat(processor.processPendingBatch(1)).isZero();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        assertThat(processor.processPendingBatch(1)).isEqualTo(1);
        assertThat(tasks.existsById(failed.getId())).isTrue();
        assertThat(tasks.existsById(next.getId())).isFalse();
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void successfulPdfDeletionDoesNotDiscardFailedOriginalDeletionTask() {
        S3ObjectDeletionTask original = task(now());
        S3ObjectDeletionTask pdf = task("review-pdfs/", now());
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            DeleteObjectRequest request = call.getArgument(0);
            if (request.key().equals(original.getObjectKey())) {
                throw S3Exception.builder().statusCode(500).message("원본 삭제 실패").build();
            }
            return DeleteObjectResponse.builder().build();
        });
        assertThat(processor.processPendingBatch(5)).isEqualTo(1);
        assertThat(tasks.existsById(original.getId())).isTrue();
        assertThat(tasks.existsById(pdf.getId())).isFalse();
    }

    @Test
    void versionDeletionWaitsForUploadUrlExpiryBeforeDeletingOriginalAndPdf() {
        String docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        String[] identity = new String[2];
        Long[] ids = new Long[2];
        List<String> keys = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AppUser author = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                    "s3-deletion@example.invalid", true, "업로더");
            em.persist(author);
            userId = author.getId();
            identity[0] = author.getIssuer();
            identity[1] = author.getSubject();
            Workspace workspace = new Workspace("S3 삭제 흐름 테스트", author);
            em.persist(workspace);
            workspaceId = workspace.getId();
            em.persist(new WorkspaceMember(workspace, author));
            Document document = new Document("문서", 1, author, null, workspace);
            em.persist(document);
            StoreFile original = new StoreFile(workspace, "workspaces/" + workspaceId + "/uploads/" + UUID.randomUUID(),
                    "원본.docx", docx, 1024L);
            StoreFile pdf = new StoreFile(workspace, "workspaces/" + workspaceId + "/review-pdfs/" + UUID.randomUUID(),
                    "변환.pdf", "application/pdf", 2048L);
            em.persist(original);
            em.persist(pdf);
            keys.add(original.getObjectKey());
            keys.add(pdf.getObjectKey());
            DocumentUpload upload = new DocumentUpload(workspace, document, author, original, 1024L, now().plusMinutes(5));
            em.persist(upload);
            upload.complete(document);
            upload.attachReviewPdf(pdf);
            DocumentVersion version = new DocumentVersion(1, "", 2, document, original.getObjectKey(),
                    original.getFileName(), 1024L, author);
            em.persist(version);
            DocumentConversionJob job = new DocumentConversionJob(upload, version);
            em.persist(job);
            job.startProcessing();
            job.complete();
            ids[0] = document.getId();
            ids[1] = version.getId();
        });

        deletionService.deleteVersion(workspaceId, ids[0], ids[1], identity[0], identity[1]);

        assertThat(processor.processPendingBatch(5)).isZero();
        verifyNoInteractions(s3Client);
        assertThat(jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key like ?",
                Long.class, "workspaces/" + workspaceId + "/%")).isEqualTo(2);
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertThat(processor.processPendingBatch(5)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key like ?",
                Long.class, "workspaces/" + workspaceId + "/%")).isZero();
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(captor.capture());
        assertThat(captor.getAllValues()).extracting(DeleteObjectRequest::key).containsExactlyInAnyOrderElementsOf(keys);
    }

    private S3ObjectDeletionTask task(LocalDateTime readyAt) {
        return task("uploads/", readyAt);
    }

    private S3ObjectDeletionTask task(String folder, LocalDateTime readyAt) {
        S3ObjectDeletionTask task = tasks.saveAndFlush(new S3ObjectDeletionTask(
                "workspaces/987654321/" + folder + UUID.randomUUID(), readyAt));
        taskIds.add(task.getId());
        return task;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
