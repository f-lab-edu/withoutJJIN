package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConversionException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionRetryService;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionService;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionDeletionService;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionTransactions;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionWorker;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 실제 커밋과 서로 다른 트랜잭션의 경합을 검증한다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentConversionRetryServiceIntegrationTest {

    @Autowired private DocumentConversionRetryService service;
    @Autowired private DocumentVersionDeletionService deletionService;
    @Autowired private DocumentConversionTransactions conversionTransactions;
    @Autowired private DocumentConversionWorker worker;
    @Autowired private DocumentConversionJobRepository jobs;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private S3Client s3Client;
    @MockitoBean private DocumentConversionService conversionService;

    private AppUser author;
    private Long workspaceId;
    private Long documentId;
    private Long versionId;
    private Long uploadId;
    private Long originalId;
    private Long jobId;
    private String originalKey;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            author = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                    "conversion-retry-integration@example.invalid", true, "업로더");
            em.persist(author);
            Workspace workspace = new Workspace("재시도 동시성 테스트", author);
            em.persist(workspace);
            workspaceId = workspace.getId();
            em.persist(new WorkspaceMember(workspace, author));
            Document document = new Document("실패한 변환", 1, author, null, workspace);
            em.persist(document);
            documentId = document.getId();
            originalKey = "workspaces/" + workspaceId + "/uploads/" + UUID.randomUUID();
            StoreFile original = new StoreFile(workspace, originalKey, "원본.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
            em.persist(original);
            originalId = original.getId();
            DocumentUpload upload = new DocumentUpload(workspace, document, author, original, 1024L,
                    LocalDateTime.of(2030, 1, 1, 12, 0));
            upload.complete(document);
            em.persist(upload);
            uploadId = upload.getId();
            DocumentVersion version = new DocumentVersion(1, "원래 버전 유지", null, document,
                    originalKey, original.getFileName(), 1024L, author);
            em.persist(version);
            versionId = version.getId();
            DocumentConversionJob job = new DocumentConversionJob(upload, version);
            job.startProcessing();
            job.fail();
            em.persist(job);
            jobId = job.getId();
        });
    }

    @AfterEach
    void cleanUpOnlyTestFixtures() {
        verifyNoInteractions(s3Client);
        jdbc.update("delete from s3_object_deletion_task where object_key = ?", originalKey);
        jdbc.update("delete from document_conversion_job where id = ?", jobId);
        jdbc.update("delete from document_upload where id = ?", uploadId);
        jdbc.update("delete from document_version where id = ?", versionId);
        jdbc.update("delete from store_file where workspace_id = ?", workspaceId);
        jdbc.update("delete from document where id = ?", documentId);
        jdbc.update("delete from workspace_member where workspace_id = ?", workspaceId);
        jdbc.update("delete from workspace where id = ?", workspaceId);
        jdbc.update("delete from app_user where id = ?", author.getId());
    }

    @Test
    @DisplayName("재시도 상태가 커밋되고 기존 대기 작업 조회와 작업자가 같은 작업을 다시 점유한다")
    void commitsRetryAndMakesExistingJobAvailableToWorker() {
        Object createdAt = jdbc.queryForObject("select created_at from document_conversion_job where id = ?",
                Object.class, jobId);

        retry();

        assertResourcesPreserved("PENDING");
        assertThat(jobs.findPendingIds(PageRequest.of(0, 100))).contains(jobId);
        assertThat(jdbc.queryForObject("select created_at from document_conversion_job where id = ?",
                Object.class, jobId)).isEqualTo(createdAt);
        DocumentConversionTransactions.PreparedConversion claimed = conversionTransactions.claim(jobId).orElseThrow();
        assertThat(claimed.documentVersionId()).isEqualTo(versionId);
        assertThat(claimed.originalFileId()).isEqualTo(originalId);
        assertThat(claimed.originalObjectKey()).isEqualTo(originalKey);
        assertResourcesPreserved("PROCESSING");
    }

    @Test
    @DisplayName("재시도한 기존 작업을 변환 작업자가 완료하고 같은 버전에 PDF와 페이지 수를 연결한다")
    void completesRetriedJobWithExistingVersionAndUpload() {
        StoredPdf pdf = new StoredPdf("workspaces/" + workspaceId + "/review-pdfs/" + UUID.randomUUID() + ".pdf",
                2048L, 2);
        when(conversionService.convertAndStore(workspaceId, originalKey)).thenReturn(pdf);
        retry();

        assertThat(worker.process(jobId)).isTrue();

        assertResourcesPreserved("COMPLETED");
        Long pdfId = jdbc.queryForObject("select review_pdf_file_id from document_upload where id = ?",
                Long.class, uploadId);
        assertThat(pdfId).isNotNull().isNotEqualTo(originalId);
        assertThat(jdbc.queryForObject("select object_key from store_file where id = ?", String.class, pdfId))
                .isEqualTo(pdf.objectKey());
        assertThat(jdbc.queryForObject("select page_count from document_version where id = ?", Integer.class, versionId))
                .isEqualTo(2);
        assertThat(worker.process(jobId)).isFalse();
        verify(conversionService, times(1)).convertAndStore(workspaceId, originalKey);
    }

    @Test
    @DisplayName("재시도 트랜잭션이 롤백되면 FAILED를 유지하고 작업자가 변환하지 않는다")
    void doesNotQueueRetryWhenTransactionRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            retry();
            em.flush();
            assertThat(jdbc.queryForObject("select status from document_conversion_job where id = ?",
                    String.class, jobId)).isEqualTo("PENDING");
            status.setRollbackOnly();
        });

        assertResourcesPreserved("FAILED");
        assertThat(jobs.findPendingIds(PageRequest.of(0, 100))).doesNotContain(jobId);
        assertThat(worker.process(jobId)).isFalse();
        verifyNoInteractions(conversionService);
    }

    @Test
    @DisplayName("동시 재시도 중 하나만 성공하고 나머지는 409이며 기존 작업 한 개만 유지된다")
    void acceptsOnlyOneConcurrentRetry() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> retryAfterBarrier(start));
            Future<Integer> second = executor.submit(() -> retryAfterBarrier(start));
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
            assertResourcesPreserved("PENDING");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("변환이 다시 실패하면 같은 작업과 버전으로 다시 재시도할 수 있다")
    void retriesAgainAfterAnotherConversionFailure() {
        WordPdfConversionException failure = new WordPdfConversionException("변환 서버 연결 실패");
        when(conversionService.convertAndStore(workspaceId, originalKey)).thenThrow(failure);
        retry();

        assertThatThrownBy(() -> worker.process(jobId)).isSameAs(failure);
        assertResourcesPreserved("FAILED");
        // 다시 실패한 작업은 사용자가 재요청하기 전까지 자동 처리하지 않는다.
        assertThat(jobs.findPendingIds(PageRequest.of(0, 100))).doesNotContain(jobId);
        assertThat(worker.process(jobId)).isFalse();
        verify(conversionService, times(1)).convertAndStore(workspaceId, originalKey);

        retry();

        assertResourcesPreserved("PENDING");
    }

    @Test
    @DisplayName("버전 삭제와 재시도가 겹쳐도 교착 없이 삭제되며 작업이 다시 생성되지 않는다")
    void serializesRetryWithVersionDeletion() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> retried = executor.submit(() -> retryAfterBarrier(start));
            Future<?> deleted = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                deletionService.deleteVersion(workspaceId, documentId, versionId,
                        author.getIssuer(), author.getSubject());
                return null;
            });
            assertThat(retried.get(30, TimeUnit.SECONDS)).isIn(202, 404);
            deleted.get(30, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from document_version where id = ?", Long.class, versionId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from document_conversion_job where id = ?", Long.class, jobId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key = ?",
                    Long.class, originalKey)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsInvalidServicePathIdsBeforeChangingJob() {
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertBadRequest(() -> service.retryConversion(invalid, documentId, versionId, author.getIssuer(), author.getSubject()));
            assertBadRequest(() -> service.retryConversion(workspaceId, invalid, versionId, author.getIssuer(), author.getSubject()));
            assertBadRequest(() -> service.retryConversion(workspaceId, documentId, invalid, author.getIssuer(), author.getSubject()));
        }
        assertResourcesPreserved("FAILED");
    }

    private void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }

    private int retryAfterBarrier(CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            retry();
            return 202;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private void retry() {
        service.retryConversion(workspaceId, documentId, versionId, author.getIssuer(), author.getSubject());
    }

    private void assertResourcesPreserved(String state) {
        assertThat(jdbc.queryForObject("select status from document_conversion_job where id = ?", String.class, jobId))
                .isEqualTo(state);
        assertThat(jdbc.queryForObject("select count(*) from document_conversion_job where document_version_id = ?",
                Long.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select document_upload_id from document_conversion_job where id = ?",
                Long.class, jobId)).isEqualTo(uploadId);
        assertThat(jdbc.queryForObject("select original_file_id from document_upload where id = ?",
                Long.class, uploadId)).isEqualTo(originalId);
        assertThat(jdbc.queryForObject("select version_number from document_version where id = ?",
                Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from document where id = ?", Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select object_key from store_file where id = ?", String.class, originalId))
                .isEqualTo(originalKey);
    }
}
