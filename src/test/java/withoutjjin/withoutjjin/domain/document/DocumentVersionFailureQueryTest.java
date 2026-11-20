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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionListResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentVersionFailureQueryTest {

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentVersionRepository versions;
    @MockitoBean private S3Client s3Client;
    @MockitoBean private WordPdfConverter converter;

    private AppUser author;
    private Workspace workspace;
    private Document document;

    @BeforeEach
    void setUp() {
        author = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "version-failure-query@example.invalid", true, "버전 업로더");
        em.persist(author);
        workspace = new Workspace("실패 정보 조회", author);
        em.persist(workspace);
        document = new Document("실패한 문서", 0, author, null, workspace);
        em.persist(document);
    }

    @AfterEach
    void doesNotCallExternalServices() {
        verifyNoInteractions(s3Client, converter);
    }

    @Test
    @DisplayName("실패 정보와 기존 버전 메타데이터를 같은 조회에서 반환한다")
    void returnsPersistedFailureWithVersionMetadata() {
        Fixture fixture = version(document, 1, true);
        LocalDateTime persistedCreatedAt = jdbc.queryForObject("select created_at from document_version where id = ?",
                LocalDateTime.class, fixture.versionId());
        LocalDateTime persistedFailedAt = jdbc.queryForObject("select failed_at from document_conversion_job where id = ?",
                LocalDateTime.class, fixture.jobId());

        DocumentVersionListResponse result = history().get(0);

        assertThat(result.id()).isEqualTo(fixture.versionId());
        assertThat(result.documentId()).isEqualTo(document.getId());
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.originalFileName()).isEqualTo("원본 V1.docx");
        assertThat(result.fileSize()).isEqualTo(1024L);
        assertThat(result.pageCount()).isNull();
        assertThat(result.uploadedBy()).isEqualTo(author.getId());
        assertThat(result.uploadedByDisplayName()).isEqualTo("버전 업로더");
        assertThat(result.createdAt()).isEqualTo(persistedCreatedAt);
        assertThat(result.conversionStatus()).isEqualTo(DocumentConversionJobStatus.FAILED);
        assertThat(result.failureStage()).isEqualTo(DocumentConversionFailureStage.ORIGINAL_DOWNLOAD);
        assertThat(result.failureCode()).isEqualTo(DocumentConversionFailureCode.ACCESS_DENIED);
        assertThat(result.failedAt()).isEqualTo(persistedFailedAt).isNotNull();
    }

    @Test
    @DisplayName("기존 FAILED 작업에 실패 정보가 없어도 null을 반환한다")
    void preservesLegacyFailureWithNullDetails() {
        Fixture fixture = version(document, 1, true);
        jdbc.update("update document_conversion_job set failure_stage = null, failure_code = null, failed_at = null where id = ?",
                fixture.jobId());

        DocumentVersionListResponse result = history().get(0);

        assertThat(result.conversionStatus()).isEqualTo(DocumentConversionJobStatus.FAILED);
        assertNoFailureDetails(result);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED"})
    @DisplayName("FAILED 이외 상태의 이전 실패 정보는 응답에서 숨기고 DB는 변경하지 않는다")
    void hidesStaleFailureDetailsWithoutWritingToDatabase(DocumentConversionJobStatus status) {
        Fixture fixture = version(document, 1, true);
        jdbc.update("update document_conversion_job set status = ? where id = ?", status.name(), fixture.jobId());

        DocumentVersionListResponse result = history().get(0);

        assertThat(result.conversionStatus()).isEqualTo(status);
        assertNoFailureDetails(result);
        assertThat(jdbc.queryForObject("select failure_stage from document_conversion_job where id = ?",
                String.class, fixture.jobId())).isEqualTo("ORIGINAL_DOWNLOAD");
        assertThat(jdbc.queryForObject("select failed_at from document_conversion_job where id = ?",
                Object.class, fixture.jobId())).isNotNull();
    }

    @Test
    @DisplayName("작업 없는 최신 버전을 유지하고 문서별 버전 번호 내림차순과 실패 정보 연결을 보존한다")
    void preservesMissingJobsOrderAndDocumentScope() {
        Fixture failed = version(document, 1, true);
        Fixture newest = version(document, 3, false);
        Fixture middle = version(document, 2, true);
        Document other = new Document("같은 팀 다른 문서", 0, author, null, workspace);
        em.persist(other);
        version(other, 1, true);

        List<DocumentVersionListResponse> result = history();

        assertThat(result).extracting(DocumentVersionListResponse::id)
                .containsExactly(newest.versionId(), middle.versionId(), failed.versionId());
        assertThat(result.get(0).conversionStatus()).isNull();
        assertNoFailureDetails(result.get(0));
        assertThat(result.get(1).failureCode()).isEqualTo(DocumentConversionFailureCode.ACCESS_DENIED);
        assertThat(result.get(2).failureCode()).isEqualTo(DocumentConversionFailureCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("DocumentVersion이 없으면 기존처럼 빈 목록을 반환한다")
    void preservesEmptyHistory() {
        assertThat(history()).isEmpty();
    }

    private List<DocumentVersionListResponse> history() {
        em.flush();
        em.clear();
        return versions.findAllWithConversionStatusByDocumentId(document.getId());
    }

    private void assertNoFailureDetails(DocumentVersionListResponse result) {
        assertThat(result.failureStage()).isNull();
        assertThat(result.failureCode()).isNull();
        assertThat(result.failedAt()).isNull();
    }

    private Fixture version(Document target, int number, boolean withJob) {
        StoreFile original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "원본 V" + number + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, target,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), author);
        em.persist(version);
        if (number > target.getRevision()) target.updateRevision(number);
        if (!withJob) {
            em.flush();
            return new Fixture(version.getId(), null);
        }
        DocumentUpload upload = new DocumentUpload(workspace, target, author, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 0, 0));
        upload.complete(target);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        job.startProcessing();
        job.fail(DocumentConversionFailureStage.ORIGINAL_DOWNLOAD, DocumentConversionFailureCode.ACCESS_DENIED);
        em.persist(job);
        em.flush();
        return new Fixture(version.getId(), job.getId());
    }

    private record Fixture(Long versionId, Long jobId) { }
}
