package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentConversionJobPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DocumentConversionJobRepository repository;

    private AppUser requester;
    private Document document;
    private StoreFile original;
    private DocumentUpload upload;
    private DocumentVersion version;

    @BeforeEach
    void setUp() {
        requester = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "conversion-job-test@example.com", true, "변환 요청자");
        entityManager.persist(requester);
        Workspace workspace = new Workspace("변환 작업 저장소 테스트팀", requester);
        entityManager.persist(workspace);
        original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        entityManager.persist(original);
        document = new Document("기획서", 1, requester, null, workspace);
        entityManager.persist(document);
        version = version(1);
        entityManager.persist(version);
        upload = new DocumentUpload(workspace, null, requester, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        entityManager.persist(upload);
    }

    @Test
    @DisplayName("변환 작업을 저장하고 문서 버전으로 조회하여 관계와 초기 상태를 확인한다")
    void savesAndReloadsPendingJob() {
        DocumentConversionJob job = repository.saveAndFlush(new DocumentConversionJob(upload, version));
        Long jobId = job.getId();
        Long uploadId = upload.getId();
        Long versionId = version.getId();
        entityManager.clear();

        DocumentConversionJob found = repository.findByDocumentVersionId(versionId).orElseThrow();

        assertThat(found.getId()).isEqualTo(jobId);
        assertThat(found.getDocumentUpload().getId()).isEqualTo(uploadId);
        assertThat(found.getDocumentVersion().getId()).isEqualTo(versionId);
        assertThat(found.getStatus()).isEqualTo(DocumentConversionJobStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(entityManager.createNativeQuery("select status from document_conversion_job where id = :id")
                .setParameter("id", jobId).getSingleResult()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("같은 문서 버전에 변환 작업을 중복 저장할 수 없다")
    void rejectsDuplicateConversionJob() {
        repository.saveAndFlush(new DocumentConversionJob(upload, version));
        StoreFile otherOriginal = new StoreFile(upload.getWorkspace(), original.getObjectKey() + "-other",
                original.getFileName(), original.getMediaType(), original.getSizeBytes());
        entityManager.persist(otherOriginal);
        DocumentUpload otherUpload = new DocumentUpload(upload.getWorkspace(), null, requester, otherOriginal,
                1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
        otherUpload.complete(document);
        entityManager.persist(otherUpload);
        DocumentConversionJob duplicate = new DocumentConversionJob(upload, version);
        // 생성자 검증과 별개로 DocumentVersion의 DB 유일 제약을 독립적으로 검증한다.
        ReflectionTestUtils.setField(duplicate, "documentUpload", otherUpload);

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_conversion_job_document_version");
    }

    @Test
    @DisplayName("같은 업로드에 다른 문서 버전의 변환 작업을 추가 저장할 수 없다")
    void rejectsUploadSharedByMultipleConversionJobs() {
        repository.saveAndFlush(new DocumentConversionJob(upload, version));
        DocumentVersion secondVersion = version(2);
        entityManager.persist(secondVersion);

        assertThatThrownBy(() -> repository.saveAndFlush(new DocumentConversionJob(upload, secondVersion)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_conversion_job_document_upload");
    }

    @Test
    @DisplayName("DocumentConversionJob을 비관적 쓰기 잠금으로 조회한다")
    void readsJobWithPessimisticWriteLock() {
        Long jobId = repository.saveAndFlush(new DocumentConversionJob(upload, version)).getId();
        entityManager.clear();

        DocumentConversionJob locked = repository.findByIdForUpdate(jobId).orElseThrow();

        assertThat(locked.getId()).isEqualTo(jobId);
        assertThat(entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("변환 작업의 실행·완료·실패 상태 변경을 DB에 저장한다")
    void persistsJobStatusTransitions(DocumentConversionJobStatus finalStatus) {
        Long jobId = repository.saveAndFlush(new DocumentConversionJob(upload, version)).getId();
        entityManager.clear();

        DocumentConversionJob found = repository.findByIdForUpdate(jobId).orElseThrow();
        found.startProcessing();
        repository.flush();
        assertThat(entityManager.createNativeQuery("select status from document_conversion_job where id = :id")
                .setParameter("id", jobId).getSingleResult()).isEqualTo("PROCESSING");

        if (finalStatus == DocumentConversionJobStatus.COMPLETED) {
            found.complete();
        } else {
            found.fail();
        }
        repository.flush();
        entityManager.clear();

        assertThat(repository.findById(jobId).orElseThrow().getStatus()).isEqualTo(finalStatus);
        assertThat(entityManager.createNativeQuery("select status from document_conversion_job where id = :id")
                .setParameter("id", jobId).getSingleResult()).isEqualTo(finalStatus.name());
    }

    private DocumentVersion version(int versionNumber) {
        return new DocumentVersion(versionNumber, "", null, document,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), requester);
    }
}
