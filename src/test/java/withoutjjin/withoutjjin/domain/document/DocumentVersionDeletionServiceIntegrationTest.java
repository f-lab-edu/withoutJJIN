package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.S3ObjectDeletionTask;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionService;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionDeletionService;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadVerifier;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionTransactions;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

// 커밋 후 조회와 실패 시 롤백을 확인하므로 테스트 메서드에 @Transactional을 붙이지 않는다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentVersionDeletionServiceIntegrationTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private DocumentVersionDeletionService service;
    @Autowired private DocumentUploadCompletionService completionService;
    @Autowired private DocumentConversionTransactions conversionTransactions;
    @Autowired private S3ObjectDeletionTaskRepository deletionTasks;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private S3Client s3Client;
    @MockitoBean private S3UploadVerifier verifier;

    private AppUser author;
    private AppUser member;
    private AppUser outsider;
    private Workspace workspace;
    private Document document;
    private VersionFixture v1;
    private VersionFixture v2;
    private VersionFixture v3;
    private final List<Long> workspaceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transaction().executeWithoutResult(status -> {
            author = user("업로더");
            member = user("다른 멤버");
            outsider = user("비멤버");
            workspace = new Workspace("선택한 버전 삭제 테스트", author);
            em.persist(workspace);
            workspaceIds.add(workspace.getId());
            em.persist(new WorkspaceMember(workspace, author));
            em.persist(new WorkspaceMember(workspace, member));
            document = new Document("보존할 Document", 3, author, null, workspace);
            em.persist(document);
            v1 = version(1, member);
            v2 = version(2, author);
            v3 = version(3, author);
            em.persist(new DocumentFeedback(em.find(DocumentVersion.class, v3.versionId()), member,
                    2, .2, .3, .1, .1, FeedbackColor.PINK, "동료가 남긴 V3 의견"));
        });
    }

    @AfterEach
    void cleanUpOnlyTestFixtures() {
        verifyNoInteractions(s3Client);
        for (Long workspaceId : workspaceIds) {
            jdbc.update("delete from s3_object_deletion_task where object_key like ?",
                    "workspaces/" + workspaceId + "/%");
            jdbc.update("delete f from document_feedback f join document_version v on f.document_version_id = v.id "
                    + "join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
            jdbc.update("delete j from document_conversion_job j join document_version v on j.document_version_id = v.id "
                    + "join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
            jdbc.update("delete from document_upload where workspace_id = ?", workspaceId);
            jdbc.update("delete v from document_version v join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
            jdbc.update("delete from store_file where workspace_id = ?", workspaceId);
            jdbc.update("delete from document where workspace_id = ?", workspaceId);
            jdbc.update("delete from workspace_member where workspace_id = ?", workspaceId);
            jdbc.update("delete from workspace where id = ?", workspaceId);
        }
        jdbc.update("delete from app_user where id in (?, ?, ?)", author.getId(), member.getId(), outsider.getId());
    }

    @Test
    @DisplayName("선택한 버전의 피드백·변환 작업·업로드·파일 정보를 삭제하고 S3 삭제 작업 두 개를 함께 커밋한다")
    void deletesOnlySelectedVersionAndQueuesItsFiles() {
        delete(v3, author);

        assertRemoved(v3);
        assertThat(queuedKeys()).containsExactlyInAnyOrder(v3.originalKey(), v3.pdfKey());
        assertPresent(v1);
        assertPresent(v2);
        assertThat(count("document", document.getId())).isEqualTo(1);
        assertThat(revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from document_version where document_id = ?",
                Long.class, document.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from document_feedback where document_version_id = ?",
                Long.class, v1.versionId())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "FAILED"})
    @DisplayName("변환 대기·실패 버전은 PDF 없이 삭제할 수 있고 원본 S3 삭제 작업만 남긴다")
    void deletesVersionWithoutConvertedPdf(DocumentConversionJobStatus status) {
        withoutPdf(v3, status);

        delete(v3, author);

        assertRemoved(v3);
        assertThat(queuedKeys()).containsExactly(v3.originalKey());
        assertPresent(v1);
        assertPresent(v2);
    }

    @Test
    @DisplayName("작업자가 먼저 변환을 시작했다면 409이며 원본과 DB 데이터가 유지된다")
    void rejectsVersionClaimedByConversionWorker() {
        withoutPdf(v3, DocumentConversionJobStatus.PENDING);
        assertThat(conversionTransactions.claim(v3.jobId())).isPresent();

        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));

        assertThat(count("document_version", v3.versionId())).isEqualTo(1);
        assertThat(count("document_upload", v3.uploadId())).isEqualTo(1);
        assertThat(count("store_file", v3.originalId())).isEqualTo(1);
        assertThat(queuedKeys()).isEmpty();
        assertThat(jdbc.queryForObject("select status from document_conversion_job where id = ?",
                String.class, v3.jobId())).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Document 생성자여도 다른 업로더의 DocumentVersion은 삭제할 수 없다")
    void rejectsDocumentCreatorWhoDidNotUploadVersion() {
        assertFailure(HttpStatus.FORBIDDEN, () -> delete(v1, author));
        assertPresent(v1);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("같은 워크스페이스의 다른 멤버도 삭제할 수 없다")
    void rejectsAnotherWorkspaceMember() {
        assertFailure(HttpStatus.FORBIDDEN, () -> delete(v3, member));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("워크스페이스 비멤버는 삭제할 수 없다")
    void rejectsOutsider() {
        assertFailure(HttpStatus.FORBIDDEN, () -> delete(v3, outsider));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("업로더여도 워크스페이스 소속이 아니면 삭제할 수 없다")
    void rejectsUploaderAfterLeavingWorkspace() {
        jdbc.update("delete from workspace_member where workspace_id = ? and user_id = ?",
                workspace.getId(), author.getId());
        assertFailure(HttpStatus.FORBIDDEN, () -> delete(v3, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("없는 Document 또는 DocumentVersion은 404이며 기존 데이터가 유지된다")
    void rejectsMissingDocumentAndVersion() {
        assertFailure(HttpStatus.NOT_FOUND, () -> delete(workspace.getId(), Long.MAX_VALUE, v3.versionId(), author));
        assertFailure(HttpStatus.NOT_FOUND, () -> delete(workspace.getId(), document.getId(), Long.MAX_VALUE, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("null 또는 0 이하의 경로 ID는 400이다")
    void rejectsInvalidPathIds() {
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertFailure(HttpStatus.BAD_REQUEST, () -> delete(invalid, document.getId(), v3.versionId(), author));
            assertFailure(HttpStatus.BAD_REQUEST, () -> delete(workspace.getId(), invalid, v3.versionId(), author));
            assertFailure(HttpStatus.BAD_REQUEST, () -> delete(workspace.getId(), document.getId(), invalid, author));
        }
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("회원이 양쪽 워크스페이스에 소속돼 있어도 다른 워크스페이스의 Document 경로로 삭제할 수 없다")
    void rejectsDocumentOutsideRequestedWorkspace() {
        Long otherWorkspaceId = transaction().execute(status -> {
            Workspace other = new Workspace("다른 워크스페이스", author);
            em.persist(other);
            em.persist(new WorkspaceMember(other, author));
            workspaceIds.add(other.getId());
            return other.getId();
        });
        assertFailure(HttpStatus.NOT_FOUND, () -> delete(otherWorkspaceId, document.getId(), v3.versionId(), author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("같은 워크스페이스여도 다른 Document의 버전을 경로에 넣으면 404다")
    void rejectsVersionOutsideRequestedDocument() {
        Long otherDocumentId = transaction().execute(status -> {
            Document other = new Document("다른 Document", 0, author, null, workspace);
            em.persist(other);
            return other.getId();
        });
        assertFailure(HttpStatus.NOT_FOUND, () -> delete(workspace.getId(), otherDocumentId, v3.versionId(), author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("변환 작업 연결이 없으면 409이며 S3 삭제 작업을 등록하지 않는다")
    void rejectsMissingConversionJob() {
        jdbc.update("delete from document_conversion_job where id = ?", v3.jobId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertThat(count("document_version", v3.versionId())).isEqualTo(1);
        assertThat(count("document_upload", v3.uploadId())).isEqualTo(1);
        assertThat(count("store_file", v3.originalId())).isEqualTo(1);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("원본 저장 키가 DocumentVersion과 일치하지 않으면 409다")
    void rejectsInconsistentOriginalKey() {
        jdbc.update("update document_version set storage_key = ? where id = ?",
                v3.originalKey() + "-changed", v3.versionId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("다른 워크스페이스 경로의 저장 키는 삭제 작업에 등록하지 않는다")
    void rejectsObjectKeyOutsideWorkspacePrefix() {
        String wrongKey = "workspaces/999999999/uploads/" + UUID.randomUUID();
        jdbc.update("update store_file set object_key = ? where id = ?", wrongKey, v3.originalId());
        jdbc.update("update document_version set storage_key = ? where id = ?", wrongKey, v3.versionId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("다른 DocumentVersion이 같은 저장 키를 참조하면 원본을 삭제하지 않는다")
    void rejectsOriginalReferencedByAnotherVersion() {
        jdbc.update("update document_version set storage_key = ? where id = ?", v3.originalKey(), v1.versionId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertPresent(v3);
        assertPresent(v1);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("다른 DocumentUpload의 PDF 참조에도 같은 StoreFile이 있으면 삭제하지 않는다")
    void rejectsFileReferencedByAnotherUpload() {
        jdbc.update("update document_upload set review_pdf_file_id = ? where id = ?", v3.originalId(), v1.uploadId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("PDF 변환 완료 상태인데 PDF 연결이 없으면 409다")
    void rejectsMissingCompletedPdf() {
        jdbc.update("update document_upload set review_pdf_file_id = null where id = ?", v3.uploadId());
        assertFailure(HttpStatus.CONFLICT, () -> delete(v3, author));
        assertPresent(v3);
        assertThat(queuedKeys()).isEmpty();
    }

    @Test
    @DisplayName("DB 삭제 후 S3 삭제 작업 저장이 실패하면 피드백·버전·파일 관계가 모두 롤백된다")
    void rollsBackAllDatabaseDeletesWhenTaskInsertFails() {
        deletionTasks.saveAndFlush(new S3ObjectDeletionTask(v3.originalKey()));

        assertThatThrownBy(() -> delete(v3, author)).isInstanceOf(DataIntegrityViolationException.class);

        assertPresent(v3);
        assertThat(jdbc.queryForObject("select count(*) from document_feedback where document_version_id = ?",
                Long.class, v3.versionId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select original_file_id from document_upload where id = ?",
                Long.class, v3.uploadId())).isEqualTo(v3.originalId());
        assertThat(jdbc.queryForObject("select review_pdf_file_id from document_upload where id = ?",
                Long.class, v3.uploadId())).isEqualTo(v3.pdfId());
        assertThat(queuedKeys()).containsExactly(v3.originalKey());
    }

    @Test
    @DisplayName("삭제 요청을 다시 보내도 S3 삭제 작업이 중복 등록되지 않는다")
    void repeatedDeleteReturnsNotFoundWithoutDuplicatingTasks() {
        delete(v3, author);
        assertFailure(HttpStatus.NOT_FOUND, () -> delete(v3, author));
        assertThat(queuedKeys()).containsExactlyInAnyOrder(v3.originalKey(), v3.pdfKey());
    }

    @Test
    @DisplayName("마지막 버전까지 삭제해도 Document와 마지막 발급 번호를 유지한다")
    void keepsDocumentAfterAllVersionsAreDeleted() {
        delete(v3, author);
        delete(v2, author);
        delete(v1, member);

        assertThat(count("document", document.getId())).isEqualTo(1);
        assertThat(revision()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from document_version where document_id = ?",
                Long.class, document.getId())).isZero();
        assertThat(queuedKeys()).containsExactlyInAnyOrder(v1.originalKey(), v1.pdfKey(),
                v2.originalKey(), v2.pdfKey(), v3.originalKey(), v3.pdfKey());
    }

    @Test
    @DisplayName("V3 삭제 후 새 버전을 업로드 완료하면 V3를 재사용하지 않고 V4를 발급한다")
    void issuesNextNumberAfterDeletingLatestVersion() {
        delete(v3, author);
        Long uploadId = transaction().execute(status -> {
            StoreFile original = file("uploads/", "수정.docx", DOCX);
            DocumentUpload upload = new DocumentUpload(workspace, document, author, original,
                    1024L, LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1));
            em.persist(upload);
            return upload.getId();
        });

        CompleteUploadResponse response = completionService.completeUpload(uploadId, author.getIssuer(), author.getSubject());

        assertThat(response.documentId()).isEqualTo(document.getId());
        assertThat(revision()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select version_number from document_version where id = ?",
                Integer.class, response.documentVersionId())).isEqualTo(4);
        assertPresent(v1);
        assertPresent(v2);
    }

    @Test
    @DisplayName("동시 삭제 요청 중 하나만 성공하고 S3 삭제 작업은 파일별 한 건만 등록된다")
    void concurrentDeletesDoNotDuplicateTasks() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> deleteAfterBarrier(start));
            Future<Integer> second = executor.submit(() -> deleteAfterBarrier(start));
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 404);
            assertRemoved(v3);
            assertThat(queuedKeys()).containsExactlyInAnyOrder(v3.originalKey(), v3.pdfKey());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int deleteAfterBarrier(CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            delete(v3, author);
            return 204;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private void withoutPdf(VersionFixture fixture, DocumentConversionJobStatus status) {
        jdbc.update("update document_upload set review_pdf_file_id = null where id = ?", fixture.uploadId());
        jdbc.update("delete from store_file where id = ?", fixture.pdfId());
        jdbc.update("update document_version set page_count = null where id = ?", fixture.versionId());
        jdbc.update("update document_conversion_job set status = ? where id = ?", status.name(), fixture.jobId());
    }

    private void delete(VersionFixture fixture, AppUser requester) {
        delete(workspace.getId(), document.getId(), fixture.versionId(), requester);
    }

    private void delete(Long workspaceId, Long documentId, Long versionId, AppUser requester) {
        service.deleteVersion(workspaceId, documentId, versionId, requester.getIssuer(), requester.getSubject());
    }

    private void assertPresent(VersionFixture fixture) {
        assertThat(count("document_version", fixture.versionId())).isEqualTo(1);
        assertThat(count("document_conversion_job", fixture.jobId())).isEqualTo(1);
        assertThat(count("document_upload", fixture.uploadId())).isEqualTo(1);
        assertThat(count("store_file", fixture.originalId())).isEqualTo(1);
        assertThat(count("store_file", fixture.pdfId())).isEqualTo(1);
    }

    private void assertRemoved(VersionFixture fixture) {
        assertThat(count("document_version", fixture.versionId())).isZero();
        assertThat(count("document_conversion_job", fixture.jobId())).isZero();
        assertThat(count("document_upload", fixture.uploadId())).isZero();
        assertThat(count("store_file", fixture.originalId())).isZero();
        assertThat(count("store_file", fixture.pdfId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from document_feedback where document_version_id = ?",
                Long.class, fixture.versionId())).isZero();
    }

    private long count(String table, Long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where id = ?", Long.class, id);
    }

    private int revision() {
        return jdbc.queryForObject("select revision from document where id = ?", Integer.class, document.getId());
    }

    private List<String> queuedKeys() {
        return jdbc.queryForList("select object_key from s3_object_deletion_task where object_key like ? order by id",
                String.class, "workspaces/" + workspace.getId() + "/%");
    }

    private void assertFailure(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(expected));
    }

    private VersionFixture version(int number, AppUser uploader) {
        StoreFile original = file("uploads/", "원본-V" + number + ".docx", DOCX);
        StoreFile pdf = file("review-pdfs/", "변환-V" + number + ".pdf", "application/pdf");
        DocumentUpload upload = new DocumentUpload(workspace, document, uploader, original,
                1024L, LocalDateTime.now().plusDays(1));
        em.persist(upload);
        upload.complete(document);
        upload.attachReviewPdf(pdf);
        DocumentVersion version = new DocumentVersion(number, "", 2, document, original.getObjectKey(),
                original.getFileName(), 1024L, uploader);
        em.persist(version);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        em.persist(job);
        job.startProcessing();
        job.complete();
        em.persist(new DocumentFeedback(version, uploader, 1, .1, .1, .2, .2,
                FeedbackColor.GREEN, "V" + number + " 의견"));
        return new VersionFixture(version.getId(), upload.getId(), job.getId(), original.getId(),
                pdf.getId(), original.getObjectKey(), pdf.getObjectKey());
    }

    private StoreFile file(String folder, String name, String mediaType) {
        StoreFile file = new StoreFile(workspace,
                "workspaces/" + workspace.getId() + "/" + folder + UUID.randomUUID(), name, mediaType, 1024L);
        em.persist(file);
        return file;
    }

    private AppUser user(String name) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "delete-version@example.invalid", true, name);
        em.persist(user);
        return user;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private record VersionFixture(Long versionId, Long uploadId, Long jobId, Long originalId,
                                  Long pdfId, String originalKey, String pdfKey) {
    }
}
