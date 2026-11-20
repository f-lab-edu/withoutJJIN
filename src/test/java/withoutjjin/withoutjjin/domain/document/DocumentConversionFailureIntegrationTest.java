package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.conversion.ConvertedPdf;
import withoutjjin.withoutjjin.domain.document.conversion.DocumentConversionException;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConversionException;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectReader;
import withoutjjin.withoutjjin.domain.document.storage.S3PdfWriter;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionTransactions;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionWorker;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 실제 서비스·작업자·독립 DB 트랜잭션을 연결하고 외부 다운로드·변환·업로드만 모의 처리한다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentConversionFailureIntegrationTest {

    private static final byte[] DOCX = {1, 2};
    private static final String PRIVATE_MESSAGE = "PRIVATE_WORD_CONTENT Bearer private-token Signature=private-key";

    @Autowired private DocumentConversionWorker worker;
    @Autowired private DocumentConversionTransactions transactions;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private S3ObjectReader reader;
    @MockitoBean private WordPdfConverter converter;
    @MockitoBean private S3PdfWriter writer;
    @MockitoBean private S3Client s3Client;

    private Long userId;
    private Long workspaceId;
    private Long uploadId;
    private Long versionId;
    private Long jobId;
    private String originalKey;
    private String pdfKey;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AppUser author = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                    "failure-record-test@example.invalid", true, "실패 기록 테스트");
            em.persist(author);
            userId = author.getId();
            Workspace workspace = new Workspace("실패 단계 검증", author);
            em.persist(workspace);
            workspaceId = workspace.getId();
            originalKey = "workspaces/" + workspaceId + "/uploads/" + UUID.randomUUID();
            StoreFile original = new StoreFile(workspace, originalKey, "원본.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
            em.persist(original);
            Document document = new Document("실패 원인 검증", 1, author, null, workspace);
            em.persist(document);
            DocumentVersion version = new DocumentVersion(1, "", null, document,
                    originalKey, original.getFileName(), original.getSizeBytes(), author);
            em.persist(version);
            versionId = version.getId();
            DocumentUpload upload = new DocumentUpload(workspace, document, author, original, 1024L,
                    LocalDateTime.of(2030, 1, 1, 0, 0));
            upload.complete(document);
            em.persist(upload);
            uploadId = upload.getId();
            DocumentConversionJob job = new DocumentConversionJob(upload, version);
            em.persist(job);
            jobId = job.getId();
        });
        pdfKey = "workspaces/" + workspaceId + "/review-pdfs/" + UUID.randomUUID() + ".pdf";
        when(reader.downloadDocx(originalKey)).thenReturn(DOCX);
        when(converter.convert(DOCX)).thenReturn(new ConvertedPdf(new byte[]{3, 4, 5}, 2));
        when(writer.upload(eq(workspaceId), any(byte[].class))).thenReturn(pdfKey);
    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("실제 변환 서비스에서 발생한 예외의 단계·코드·시각을 FAILED와 함께 커밋한다")
    void commitsFailureDetailsFromRealConversionService(DocumentConversionFailureStage stage,
                                                       RuntimeException cause, DocumentConversionFailureCode code) {
        switch (stage) {
            case ORIGINAL_DOWNLOAD -> when(reader.downloadDocx(originalKey)).thenThrow(cause);
            case PDF_CONVERSION -> when(converter.convert(DOCX)).thenThrow(cause);
            case PDF_UPLOAD -> when(writer.upload(eq(workspaceId), any(byte[].class))).thenThrow(cause);
            default -> throw new IllegalArgumentException(stage.name());
        }

        assertThatThrownBy(() -> worker.process(jobId)).isInstanceOfSatisfying(DocumentConversionException.class,
                failure -> assertThat(failure.getCause()).isSameAs(cause));

        Map<String, Object> failed = jobRow();
        assertThat(failed).containsEntry("status", "FAILED")
                .containsEntry("failure_stage", stage.name()).containsEntry("failure_code", code.name());
        assertThat(failed.get("failed_at")).isNotNull();
        assertThat(failed.values().toString()).doesNotContain("PRIVATE_WORD_CONTENT", "private-token", "private-key");
        assertThat(jdbc.queryForObject("select review_pdf_file_id from document_upload where id = ?", Long.class, uploadId)).isNull();
        assertThat(jdbc.queryForObject("select page_count from document_version where id = ?", Integer.class, versionId)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from store_file where workspace_id = ?", Long.class, workspaceId)).isEqualTo(1);
        if (stage == DocumentConversionFailureStage.ORIGINAL_DOWNLOAD) verifyNoInteractions(converter, writer);
        if (stage == DocumentConversionFailureStage.PDF_CONVERSION) verifyNoInteractions(writer);

        // FAILED는 자동 처리하지 않으며 뒤늦은 실패 기록도 최초 실패 정보를 덮어쓰지 않는다.
        assertThat(worker.process(jobId)).isFalse();
        transactions.markFailed(jobId, DocumentConversionFailureStage.UNKNOWN, DocumentConversionFailureCode.UNKNOWN);
        assertThat(jobRow()).isEqualTo(failed);
    }

    static Stream<Arguments> failures() {
        DocumentConversionFailureStage download = DocumentConversionFailureStage.ORIGINAL_DOWNLOAD;
        DocumentConversionFailureStage conversion = DocumentConversionFailureStage.PDF_CONVERSION;
        DocumentConversionFailureStage upload = DocumentConversionFailureStage.PDF_UPLOAD;
        return Stream.of(
                Arguments.of(download, s3(403), DocumentConversionFailureCode.ACCESS_DENIED),
                Arguments.of(download, s3(404), DocumentConversionFailureCode.FILE_NOT_FOUND),
                Arguments.of(download, SdkClientException.create(PRIVATE_MESSAGE, new ConnectException()), DocumentConversionFailureCode.CONNECTION_FAILED),
                Arguments.of(download, SdkClientException.create(PRIVATE_MESSAGE, new SocketTimeoutException()), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(download, SdkClientException.create(PRIVATE_MESSAGE), DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(conversion, new WordPdfConversionException(PRIVATE_MESSAGE), DocumentConversionFailureCode.CONVERSION_FAILED),
                Arguments.of(conversion, new WordPdfConversionException(DocumentConversionFailureCode.INVALID_PDF, PRIVATE_MESSAGE), DocumentConversionFailureCode.INVALID_PDF),
                Arguments.of(conversion, new WordPdfConversionException(DocumentConversionFailureCode.SIZE_LIMIT_EXCEEDED, PRIVATE_MESSAGE), DocumentConversionFailureCode.SIZE_LIMIT_EXCEEDED),
                Arguments.of(conversion, new WordPdfConversionException(PRIVATE_MESSAGE,
                        new ResourceAccessException("converter", new SocketTimeoutException())), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(conversion, new IllegalStateException(PRIVATE_MESSAGE), DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(upload, s3(403), DocumentConversionFailureCode.ACCESS_DENIED),
                Arguments.of(upload, SdkClientException.create(PRIVATE_MESSAGE, new ConnectException()), DocumentConversionFailureCode.CONNECTION_FAILED));
    }

    @Test
    @DisplayName("호출자의 트랜잭션이 롤백되어도 실패 상태와 원인 기록은 독립적으로 커밋된다")
    void preservesFailureWhenCallerRollsBack() {
        S3Exception cause = s3(403);
        when(reader.downloadDocx(originalKey)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw cause;
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> worker.process(jobId)).isInstanceOf(DocumentConversionException.class);
            status.setRollbackOnly();
        });

        assertThat(jobRow()).containsEntry("status", "FAILED").containsEntry("failure_stage", "ORIGINAL_DOWNLOAD")
                .containsEntry("failure_code", "ACCESS_DENIED");
        assertThat(jobRow().get("failed_at")).isNotNull();
    }

    @Test
    @DisplayName("완료한 작업은 뒤늦은 실패 기록이 상태와 실패 정보를 변경하지 않는다")
    void doesNotOverwriteCompletedJobWithLateFailure() {
        assertThat(worker.process(jobId)).isTrue();

        transactions.markFailed(jobId, DocumentConversionFailureStage.ORIGINAL_DOWNLOAD, DocumentConversionFailureCode.ACCESS_DENIED);

        assertThat(jobRow()).containsEntry("status", "COMPLETED").containsEntry("failure_stage", null)
                .containsEntry("failure_code", null).containsEntry("failed_at", null);
        assertThat(jdbc.queryForObject("select review_pdf_file_id from document_upload where id = ?", Long.class, uploadId)).isNotNull();
    }

    private Map<String, Object> jobRow() {
        return jdbc.queryForMap("select * from document_conversion_job where id = ?", jobId);
    }

    private static S3Exception s3(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message(PRIVATE_MESSAGE).build();
    }

    @AfterEach
    void cleanUpOnlyTestWorkspace() {
        verifyNoInteractions(s3Client);
        if (workspaceId == null) return;
        jdbc.update("delete j from document_conversion_job j join document_upload u on j.document_upload_id = u.id where u.workspace_id = ?", workspaceId);
        jdbc.update("delete from document_upload where workspace_id = ?", workspaceId);
        jdbc.update("delete v from document_version v join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
        jdbc.update("delete from document where workspace_id = ?", workspaceId);
        jdbc.update("delete from store_file where workspace_id = ?", workspaceId);
        jdbc.update("delete from workspace where id = ?", workspaceId);
        jdbc.update("delete from app_user where id = ?", userId);
    }
}
