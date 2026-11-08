package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentConversionPendingJobQueryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private DocumentConversionJobRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private AppUser requester;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        requester = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "pending-job-query@example.com", true, "변환 요청자");
        entityManager.persist(requester);
        workspace = new Workspace("대기 작업 조회 테스트팀", requester);
        entityManager.persist(workspace);
    }

    @Test
    @DisplayName("PENDING 작업만 조회하고 실행 중·완료·실패 작업은 제외한다")
    void selectsOnlyPendingJobs() {
        Long pending = createJob(DocumentConversionJobStatus.PENDING, time(0));
        Long processing = createJob(DocumentConversionJobStatus.PROCESSING, time(0));
        Long completed = createJob(DocumentConversionJobStatus.COMPLETED, time(0));
        Long failed = createJob(DocumentConversionJobStatus.FAILED, time(0));
        entityManager.clear();

        List<Long> ids = repository.findPendingIds(Pageable.unpaged());

        assertThat(ids).contains(pending).doesNotContain(processing, completed, failed);
    }

    @Test
    @DisplayName("생성 시각 순으로 조회하고 같은 시각의 작업은 ID 오름차순으로 정렬한다")
    void ordersByCreationTimeThenId() {
        Long latest = createJob(DocumentConversionJobStatus.PENDING, time(2));
        Long oldest = createJob(DocumentConversionJobStatus.PENDING, time(0));
        Long sameTimeFirst = createJob(DocumentConversionJobStatus.PENDING, time(1));
        Long sameTimeSecond = createJob(DocumentConversionJobStatus.PENDING, time(1));
        entityManager.clear();
        Set<Long> testIds = Set.of(latest, oldest, sameTimeFirst, sameTimeSecond);

        List<Long> ordered = repository.findPendingIds(Pageable.unpaged()).stream()
                .filter(testIds::contains).toList();

        assertThat(ordered).containsExactly(oldest, sameTimeFirst, sameTimeSecond, latest);
    }

    @Test
    @DisplayName("요청한 건수만 반환하고 다음 페이지에서 같은 작업을 반복하지 않는다")
    void limitsResultsAndAppliesOffset() {
        for (int i = 0; i < 5; i++) {
            createJob(DocumentConversionJobStatus.PENDING, time(i));
        }
        entityManager.clear();
        // 기존 개발 DB 작업은 변경하지 않고, 전체 정렬 결과의 해당 구간과 비교한다.
        List<Long> ordered = repository.findPendingIds(Pageable.unpaged());

        List<Long> first = repository.findPendingIds(PageRequest.of(0, 2));
        List<Long> second = repository.findPendingIds(PageRequest.of(1, 2));

        assertThat(first).containsExactlyElementsOf(ordered.subList(0, 2));
        assertThat(second).containsExactlyElementsOf(ordered.subList(2, 4));
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    @Test
    @DisplayName("조회할 작업이 없는 페이지는 빈 목록을 반환한다")
    void returnsEmptyListForPageBeyondAllJobs() {
        createJob(DocumentConversionJobStatus.PENDING, time(0));
        int beyondAllJobs = Math.toIntExact(repository.count() + 1);
        entityManager.clear();

        assertThat(repository.findPendingIds(PageRequest.of(beyondAllJobs, 1))).isEmpty();
    }

    private Long createJob(DocumentConversionJobStatus status, LocalDateTime createdAt) {
        StoreFile original = new StoreFile(workspace,
                "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        entityManager.persist(original);
        Document document = new Document("기획서", 1, requester, null, workspace);
        entityManager.persist(document);
        DocumentVersion version = new DocumentVersion(1, "", null, document,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), requester);
        entityManager.persist(version);
        DocumentUpload upload = new DocumentUpload(workspace, null, requester, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        entityManager.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) {
            job.startProcessing();
        }
        if (status == DocumentConversionJobStatus.COMPLETED) {
            job.complete();
        } else if (status == DocumentConversionJobStatus.FAILED) {
            job.fail();
        }
        repository.saveAndFlush(job);
        // 실제 저장 순서와 다른 생성 시각을 준비하여 정렬을 검증한다.
        jdbc.update("UPDATE document_conversion_job SET created_at = ? WHERE id = ?", createdAt, job.getId());
        return job.getId();
    }

    private LocalDateTime time(int second) {
        return LocalDateTime.of(2026, 1, 1, 12, 0, second);
    }
}
