package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "invitation.mail.enabled=false")
@Transactional
class DocumentPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private AppUser creator;
    private AppUser uploader;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        creator = user("문서 생성자");
        uploader = user("버전 업로더");
        workspace = new Workspace("문서 테스트팀", creator);
        entityManager.persist(workspace);
    }

    @Test
    @DisplayName("문서와 여러 버전의 파일 정보 및 회원 관계를 DB에서 다시 조회한다")
    void persistsDocumentVersionsAndReloadsFileMetadataAndRelationships() {
        Document document = document("기획서");
        DocumentVersion first = version(document, 1, null);
        DocumentVersion second = version(document, 2, 8);
        entityManager.persist(first);
        entityManager.persist(second);
        document.updateRevision(2);
        entityManager.flush();

        Long documentId = document.getId();
        Long firstId = first.getId();
        Long secondId = second.getId();
        Long creatorId = creator.getId();
        Long uploaderId = uploader.getId();
        Long workspaceId = workspace.getId();
        entityManager.clear();

        Document found = entityManager.find(Document.class, documentId);
        DocumentVersion foundFirst = entityManager.find(DocumentVersion.class, firstId);
        DocumentVersion foundSecond = entityManager.find(DocumentVersion.class, secondId);

        assertThat(found.getTitle()).isEqualTo("기획서");
        assertThat(found.getRevision()).isEqualTo(2);
        assertThat(found.getCreatedBy().getId()).isEqualTo(creatorId);
        assertThat(found.getWorkspace().getId()).isEqualTo(workspaceId);
        assertThat(found.getApprover()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(foundFirst.getDocument().getId()).isEqualTo(documentId);
        assertThat(foundFirst.getVersionNumber()).isEqualTo(1);
        assertThat(foundFirst.getPageCount()).isNull();
        assertThat(foundSecond.getVersionNumber()).isEqualTo(2);
        assertThat(foundSecond.getDocument().getId()).isEqualTo(documentId);
        assertThat(foundSecond.getStorageKey()).isEqualTo("documents/" + documentId + "/v2.pdf");
        assertThat(foundSecond.getOriginalFileName()).isEqualTo("plan-v2.pdf");
        assertThat(foundSecond.getFileSize()).isEqualTo(2048L);
        assertThat(foundSecond.getChangeSummary()).isEqualTo("버전 2 수정 내용");
        assertThat(foundSecond.getPageCount()).isEqualTo(8);
        assertThat(foundSecond.getUploadedBy().getId()).isEqualTo(uploaderId);
        assertThat(foundSecond.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 문서에 같은 버전 번호를 중복 저장할 수 없다")
    void rejectsDuplicateVersionNumberWithinSameDocument() {
        Document document = document("중복 검증 문서");
        entityManager.persist(version(document, 1, 3));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(version(document, 1, 4));
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uk_document_version_document_id_version_number");
    }

    @Test
    @DisplayName("서로 다른 문서는 같은 버전 번호를 사용할 수 있다")
    void allowsSameVersionNumberForDifferentDocuments() {
        Document firstDocument = document("첫 문서");
        Document secondDocument = document("두 번째 문서");
        DocumentVersion first = version(firstDocument, 1, 2);
        DocumentVersion second = version(secondDocument, 1, 3);
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        Long firstId = first.getId();
        Long secondId = second.getId();
        entityManager.clear();

        assertThat(entityManager.find(DocumentVersion.class, firstId).getDocument().getId())
                .isNotEqualTo(entityManager.find(DocumentVersion.class, secondId).getDocument().getId());
    }

    @Test
    @DisplayName("최신 버전 번호를 이전 번호로 변경할 수 없다")
    void rejectsMovingLatestRevisionBackwards() {
        Document document = document("버전 검증 문서");
        document.updateRevision(2);

        assertThatThrownBy(() -> document.updateRevision(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(document.getRevision()).isEqualTo(2);
    }

    private AppUser user(String displayName) {
        String subject = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://accounts.google.com", subject,
                subject + "@example.com", true, displayName);
        entityManager.persist(user);
        return user;
    }

    private Document document(String title) {
        Document document = new Document(title, 0, creator, null, workspace);
        entityManager.persist(document);
        return document;
    }

    private DocumentVersion version(Document document, int number, Integer pageCount) {
        return new DocumentVersion(number, "버전 " + number + " 수정 내용", pageCount,
                document, "documents/" + document.getId() + "/v" + number + ".pdf",
                "plan-v" + number + ".pdf", 2048L, uploader);
    }
}
