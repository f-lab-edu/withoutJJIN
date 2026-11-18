package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConversionException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionService;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionWorker;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

// 작업자의 실제 커밋·롤백과 동시 실행을 검사하므로 테스트 전체를 트랜잭션으로 감싸지 않는다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentConversionWorkerIntegrationTest {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private DocumentConversionWorker worker;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private DocumentConversionService conversionService;
    @MockitoSpyBean private DocumentConversionJobRepository jobs;

    private AppUser requester;
    private Workspace workspace;
    private StoreFile original;
    private DocumentUpload upload;
    private DocumentVersion version;
    private DocumentConversionJob job;
    private StoredPdf convertedPdf;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            requester = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                    "conversion-worker-test@example.com", true, "변환 요청자");
            entityManager.persist(requester);
            workspace = new Workspace("변환 작업자 테스트팀", requester);
            entityManager.persist(workspace);
            original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                    "기획서.docx", DOCX_MEDIA_TYPE, 1024L);
            entityManager.persist(original);
            Document document = new Document("기획서", 1, requester, null, workspace);
            entityManager.persist(document);
            version = new DocumentVersion(1, "", null, document, original.getObjectKey(),
                    original.getFileName(), original.getSizeBytes(), requester);
            entityManager.persist(version);
            upload = new DocumentUpload(workspace, null, requester, original, 1024L,
                    LocalDateTime.of(2030, 1, 1, 12, 0));
            upload.complete(document);
            entityManager.persist(upload);
            job = new DocumentConversionJob(upload, version);
            entityManager.persist(job);
        });
        convertedPdf = new StoredPdf("workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf",
                4096L, 3);
    }

    @Test
    @DisplayName("변환은 트랜잭션 밖에서 실행하고 PDF 연결·페이지 수·작업 완료를 함께 커밋한다")
    void convertsOutsideTransactionAndCommitsResult() {
        stubSuccessfulConversion();

        assertThat(worker.process(job.getId())).isTrue();

        assertCompletedResult();
        assertThat(worker.process(job.getId())).isFalse();
        verify(conversionService, times(1)).convertAndStore(workspace.getId(), original.getObjectKey());
    }

    @Test
    @DisplayName("호출자의 트랜잭션이 롤백되어도 작업자에서 완료한 독립 트랜잭션은 유지한다")
    void suspendsCallerTransaction() {
        stubSuccessfulConversion();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThat(worker.process(job.getId())).isTrue();
            transaction.setRollbackOnly();
        });

        assertCompletedResult();
    }

    @ParameterizedTest
    @MethodSource("conversionFailures")
    @DisplayName("다운로드·변환·업로드 실패 시 결과를 연결하지 않고 작업을 FAILED로 기록한다")
    void recordsConversionFailure(RuntimeException failure) {
        when(conversionService.convertAndStore(workspace.getId(), original.getObjectKey())).thenThrow(failure);

        assertThatThrownBy(() -> worker.process(job.getId())).isSameAs(failure);

        assertFailedWithoutResult();
        assertThat(worker.process(job.getId())).isFalse();
        verify(conversionService, times(1)).convertAndStore(workspace.getId(), original.getObjectKey());
    }

    static Stream<RuntimeException> conversionFailures() {
        return Stream.of(SdkClientException.create("DOCX 다운로드 실패"),
                new WordPdfConversionException("DOCX PDF 변환 실패"),
                (S3Exception) S3Exception.builder().statusCode(403).message("PDF 업로드 실패").build());
    }

    @Test
    @DisplayName("PDF 연결·페이지 수·완료 상태의 SQL 실행 후 실패해도 전체 결과를 롤백한다")
    void rollsBackAllResultChangesWhenDatabaseWriteFails() {
        stubSuccessfulConversion();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("테스트용 결과 저장 실패");
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(jobStatus()).isEqualTo("COMPLETED");
            assertThat(pageCount()).isEqualTo(3);
            assertThat(reviewPdfId()).isNotNull();
            assertThat(countResultFiles()).isEqualTo(1);
            throw failure;
        }).when(jobs).flush();

        assertThatThrownBy(() -> worker.process(job.getId())).isSameAs(failure);

        assertFailedWithoutResult();
    }

    @Test
    @DisplayName("변환 중 원본 파일 연결이 변경되면 PDF를 연결하지 않는다")
    void rejectsOriginalFileChangedDuringConversion() {
        when(conversionService.convertAndStore(workspace.getId(), original.getObjectKey())).thenAnswer(invocation -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                StoreFile replacement = new StoreFile(entityManager.getReference(Workspace.class, workspace.getId()),
                        original.getObjectKey() + "-replacement", "다른파일.docx", DOCX_MEDIA_TYPE, 1024L);
                entityManager.persist(replacement);
                jdbc.update("UPDATE document_upload SET original_file_id = ? WHERE id = ?",
                        replacement.getId(), upload.getId());
            });
            return convertedPdf;
        });

        assertThatThrownBy(() -> worker.process(job.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("원본 연결");

        assertThat(jobStatus()).isEqualTo("FAILED");
        assertThat(reviewPdfId()).isNull();
        assertThat(pageCount()).isNull();
        assertThat(countResultFiles()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PROCESSING", "COMPLETED", "FAILED"})
    @DisplayName("PENDING이 아닌 작업은 변환하지 않는다")
    void skipsNonPendingJob(DocumentConversionJobStatus status) {
        jdbc.update("UPDATE document_conversion_job SET status = ? WHERE id = ?", status.name(), job.getId());

        assertThat(worker.process(job.getId())).isFalse();

        assertThat(jobStatus()).isEqualTo(status.name());
        verifyNoInteractions(conversionService);
    }

    @Test
    @DisplayName("존재하지 않는 작업은 변환하지 않는다")
    void skipsMissingJob() {
        assertThat(worker.process(Long.MAX_VALUE)).isFalse();

        assertThat(jobStatus()).isEqualTo("PENDING");
        verifyNoInteractions(conversionService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("작업 번호가 없거나 양수가 아니면 변환하지 않는다")
    void rejectsInvalidJobId(Long jobId) {
        assertThatThrownBy(() -> worker.process(jobId))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("양수");

        assertThat(jobStatus()).isEqualTo("PENDING");
        verifyNoInteractions(conversionService);
    }

    @Test
    @Timeout(30)
    @DisplayName("동시 요청은 한 작업자만 변환하며 변환 중에는 DB 행 잠금을 유지하지 않는다")
    void processesConcurrentRequestsOnlyOnceWithoutHoldingLockDuringConversion() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch conversionStarted = new CountDownLatch(1);
        CountDownLatch releaseConversion = new CountDownLatch(1);
        when(conversionService.convertAndStore(workspace.getId(), original.getObjectKey())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            conversionStarted.countDown();
            assertThat(releaseConversion.await(10, TimeUnit.SECONDS)).isTrue();
            return convertedPdf;
        });
        var executor = Executors.newFixedThreadPool(2);
        var completions = new ExecutorCompletionService<Boolean>(executor);
        try {
            for (int i = 0; i < 2; i++) {
                completions.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return worker.process(job.getId());
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(conversionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 첫 작업자가 변환을 기다리는 중에도 두 번째 요청은 잠금 대기 없이 빠져나와야 한다.
            var skipped = completions.poll(5, TimeUnit.SECONDS);
            assertThat(skipped).isNotNull();
            assertThat(skipped.get()).isFalse();
            assertThat(jobStatus()).isEqualTo("PROCESSING");
            assertThat(countResultFiles()).isZero();

            releaseConversion.countDown();
            var completed = completions.poll(10, TimeUnit.SECONDS);
            assertThat(completed).isNotNull();
            assertThat(completed.get()).isTrue();
            assertCompletedResult();
            verify(conversionService, times(1)).convertAndStore(workspace.getId(), original.getObjectKey());
        } finally {
            start.countDown();
            releaseConversion.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void stubSuccessfulConversion() {
        when(conversionService.convertAndStore(workspace.getId(), original.getObjectKey())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jobStatus()).isEqualTo("PROCESSING");
            return convertedPdf;
        });
    }

    private void assertCompletedResult() {
        assertThat(jobStatus()).isEqualTo("COMPLETED");
        assertThat(pageCount()).isEqualTo(convertedPdf.pageCount());
        assertThat(countResultFiles()).isEqualTo(1);
        Long pdfId = reviewPdfId();
        assertThat(pdfId).isNotNull().isNotEqualTo(original.getId());
        assertThat(jdbc.queryForObject("SELECT object_key FROM store_file WHERE id = ?", String.class, pdfId))
                .isEqualTo(convertedPdf.objectKey());
        assertThat(jdbc.queryForObject("SELECT file_name FROM store_file WHERE id = ?", String.class, pdfId))
                .isEqualTo("기획서.pdf");
        assertThat(jdbc.queryForObject("SELECT media_type FROM store_file WHERE id = ?", String.class, pdfId))
                .isEqualTo("application/pdf");
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM store_file WHERE id = ?", Long.class, pdfId))
                .isEqualTo(convertedPdf.sizeBytes());
        assertThat(jdbc.queryForObject("SELECT storage_key FROM document_version WHERE id = ?",
                String.class, version.getId())).isEqualTo(original.getObjectKey());
        assertThat(jdbc.queryForObject("SELECT original_file_id FROM document_upload WHERE id = ?",
                Long.class, upload.getId())).isEqualTo(original.getId());
    }

    private void assertFailedWithoutResult() {
        assertThat(jobStatus()).isEqualTo("FAILED");
        assertThat(reviewPdfId()).isNull();
        assertThat(pageCount()).isNull();
        assertThat(countResultFiles()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_file WHERE workspace_id = ?",
                Long.class, workspace.getId())).isEqualTo(1);
    }

    private String jobStatus() {
        return jdbc.queryForObject("SELECT status FROM document_conversion_job WHERE id = ?", String.class, job.getId());
    }

    private Integer pageCount() {
        return jdbc.queryForObject("SELECT page_count FROM document_version WHERE id = ?", Integer.class, version.getId());
    }

    private Long reviewPdfId() {
        return jdbc.queryForObject("SELECT review_pdf_file_id FROM document_upload WHERE id = ?", Long.class, upload.getId());
    }

    private long countResultFiles() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM store_file WHERE object_key = ?", Long.class, convertedPdf.objectKey());
    }

    @AfterEach
    void cleanUpOnlyThisTestsData() {
        if (workspace != null) {
            jdbc.update("DELETE j FROM document_conversion_job j"
                    + " JOIN document_upload u ON j.document_upload_id = u.id WHERE u.workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM document_upload WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE v FROM document_version v JOIN document d ON v.document_id = d.id WHERE d.workspace_id = ?",
                    workspace.getId());
            jdbc.update("DELETE FROM document WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM store_file WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (requester != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", requester.getId());
        }
    }
}
