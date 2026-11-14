package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.dto.DocumentListResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentListQueryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private DocumentRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private AppUser requester;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        requester = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "document-list@example.com", true, "문서 목록 조회자");
        entityManager.persist(requester);
        workspace = workspace("문서 목록 테스트팀");
    }

    @Test
    @DisplayName("해당 워크스페이스의 문서와 응답 필드만 조회하고 다른 팀 문서는 제외한다")
    void selectsOnlyRequestedWorkspaceDocuments() {
        Document selected = document(workspace, "우리 팀 기획서", 1, time(1));
        addVersion(selected, 1, DocumentConversionJobStatus.PENDING);
        Document other = document(workspace("다른 팀"), "다른 팀 기획서", 1, time(2));
        addVersion(other, 1, DocumentConversionJobStatus.COMPLETED);
        flushAndClear();

        List<DocumentListResponse> responses = findDocuments();

        assertThat(responses).containsExactly(new DocumentListResponse(
                selected.getId(), "우리 팀 기획서", 1, time(1), DocumentConversionJobStatus.PENDING));
    }

    @Test
    @DisplayName("작업 저장 순서가 아닌 Document.revision에 해당하는 최신 버전의 상태만 반환한다")
    void selectsLatestRevisionStatusWithoutDuplicatingDocument() {
        Document document = document(workspace, "버전이 여러 개인 문서", 2, time(0));
        // 최신 버전을 먼저 저장하여 작업 ID의 최댓값을 잘못 선택하는 구현도 검증한다.
        addVersion(document, 2, DocumentConversionJobStatus.COMPLETED);
        addVersion(document, 1, DocumentConversionJobStatus.FAILED);
        flushAndClear();

        assertThat(findDocuments()).containsExactly(new DocumentListResponse(
                document.getId(), document.getTitle(), 2, time(0), DocumentConversionJobStatus.COMPLETED));
    }

    @ParameterizedTest
    @EnumSource(DocumentConversionJobStatus.class)
    @DisplayName("대기·실행 중·완료·실패 상태의 문서를 모두 목록에 포함한다")
    void includesEveryConversionStatus(DocumentConversionJobStatus status) {
        Document document = document(workspace, "상태 확인 문서", 1, time(0));
        addVersion(document, 1, status);
        flushAndClear();

        assertThat(findDocuments()).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(document.getId());
            assertThat(response.conversionStatus()).isEqualTo(status);
        });
    }

    @Test
    @DisplayName("아직 버전이 없는 문서도 포함하고 변환 상태는 null로 반환한다")
    void includesDocumentWithoutVersion() {
        Document document = document(workspace, "아직 파일이 없는 문서", 0, time(0));
        flushAndClear();

        assertThat(findDocuments()).containsExactly(new DocumentListResponse(
                document.getId(), document.getTitle(), 0, time(0), null));
    }

    @Test
    @DisplayName("최신 버전에 변환 작업이 없으면 이전 버전의 상태를 대신 반환하지 않는다")
    void returnsNullWhenLatestVersionHasNoConversionJob() {
        Document document = document(workspace, "최신 변환 작업이 없는 문서", 2, time(0));
        addVersion(document, 1, DocumentConversionJobStatus.COMPLETED);
        addVersion(document, 2, null);
        flushAndClear();

        assertThat(findDocuments()).containsExactly(new DocumentListResponse(
                document.getId(), document.getTitle(), 2, time(0), null));
    }

    @Test
    @DisplayName("생성 시각 내림차순으로 정렬하고 같은 시각에는 ID 내림차순으로 정렬한다")
    void ordersByCreationTimeThenIdDescending() {
        Document latest = document(workspace, "가장 최근 문서", 0, time(2));
        Document oldest = document(workspace, "가장 오래된 문서", 0, time(0));
        Document sameTimeFirst = document(workspace, "같은 시각 첫 문서", 0, time(1));
        Document sameTimeSecond = document(workspace, "같은 시각 두 번째 문서", 0, time(1));
        flushAndClear();

        assertThat(findDocuments()).extracting(DocumentListResponse::id)
                .containsExactly(latest.getId(), sameTimeSecond.getId(), sameTimeFirst.getId(), oldest.getId());
    }

    @Test
    @DisplayName("다른 팀에 문서가 있어도 해당 워크스페이스에 문서가 없으면 빈 목록을 반환한다")
    void returnsEmptyListForWorkspaceWithoutDocuments() {
        document(workspace("문서가 있는 다른 팀"), "다른 팀 문서", 0, time(0));
        flushAndClear();

        assertThat(findDocuments()).isEmpty();
    }

    private List<DocumentListResponse> findDocuments() {
        return repository.findAllWithLatestConversionStatusByWorkspaceId(workspace.getId());
    }

    private Workspace workspace(String name) {
        Workspace created = new Workspace(name, requester);
        entityManager.persist(created);
        return created;
    }

    private Document document(Workspace target, String title, int revision, LocalDateTime createdAt) {
        Document document = new Document(title, revision, requester, null, target);
        entityManager.persist(document);
        entityManager.flush();
        // 테스트에서 생성한 문서의 시각만 바꾸고 종료 시 트랜잭션을 롤백한다.
        jdbc.update("UPDATE document SET created_at = ? WHERE id = ?", createdAt, document.getId());
        return document;
    }

    private void addVersion(Document document, int number, DocumentConversionJobStatus status) {
        Workspace target = document.getWorkspace();
        StoreFile original = new StoreFile(target,
                "workspaces/" + target.getId() + "/uploads/" + UUID.randomUUID() + ".docx",
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        entityManager.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, document,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), requester);
        entityManager.persist(version);
        if (status == null) {
            return;
        }
        DocumentUpload upload = new DocumentUpload(target, null, requester, original,
                1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
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
        entityManager.persist(job);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private LocalDateTime time(int second) {
        return LocalDateTime.of(2026, 1, 1, 12, 0, second);
    }
}
