package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentUploadPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private AppUser requester;
    private Workspace workspace;
    private final LocalDateTime expiresAt = LocalDateTime.of(2030, 1, 1, 12, 0);

    @BeforeEach
    void setUp() {
        requester = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "upload-entity-test@example.com", true, "업로드 요청자");
        entityManager.persist(requester);
        workspace = new Workspace("업로드 엔티티 테스트팀", requester);
        entityManager.persist(workspace);
    }

    @Test
    @DisplayName("원본 파일 정보와 업로드 요청을 저장하고 DB에서 관계를 다시 조회한다")
    void savesAndReloadsPendingUploadWithOriginalFile() {
        StoreFile original = file(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID());
        entityManager.persist(original);
        DocumentUpload upload = new DocumentUpload(workspace, null, requester, original, 1024L, expiresAt);
        entityManager.persist(upload);
        entityManager.flush();
        Long uploadId = upload.getId();
        Long fileId = original.getId();
        Long requesterId = requester.getId();
        Long workspaceId = workspace.getId();
        String objectKey = original.getObjectKey();
        entityManager.clear();

        DocumentUpload found = entityManager.find(DocumentUpload.class, uploadId);
        assertThat(found.getWorkspace().getId()).isEqualTo(workspaceId);
        assertThat(found.getCreatedBy().getId()).isEqualTo(requesterId);
        assertThat(found.getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
        assertThat(found.getDocument()).isNull();
        assertThat(found.getReviewPdfFile()).isNull();
        assertThat(found.getReservedBytes()).isEqualTo(1024L);
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getCreatedAt()).isNotNull();
        StoreFile foundFile = found.getOriginalFile();
        assertThat(foundFile.getId()).isEqualTo(fileId);
        assertThat(foundFile.getWorkspace().getId()).isEqualTo(workspaceId);
        assertThat(foundFile.getObjectKey()).isEqualTo(objectKey);
        assertThat(foundFile.getFileName()).isEqualTo("기획서.docx");
        assertThat(foundFile.getMediaType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(foundFile.getSizeBytes()).isEqualTo(1024L);
        assertThat(foundFile.getCreatedAt()).isNotNull();
        assertThat(entityManager.createNativeQuery("select status from document_upload where id = :id")
                .setParameter("id", uploadId).getSingleResult()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("문서와 원본 및 PDF 파일이 아직 없는 준비 작업도 저장할 수 있다")
    void permitsUnassignedDocumentAndFilesWhilePending() {
        DocumentUpload upload = new DocumentUpload(workspace, null, requester, null, 0L, expiresAt);
        entityManager.persist(upload);
        entityManager.flush();
        Long id = upload.getId();
        entityManager.clear();

        DocumentUpload found = entityManager.find(DocumentUpload.class, id);
        assertThat(found.getDocument()).isNull();
        assertThat(found.getOriginalFile()).isNull();
        assertThat(found.getReviewPdfFile()).isNull();
        assertThat(found.getReservedBytes()).isZero();
        assertThat(found.getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
    }

    @Test
    @DisplayName("같은 팀의 기존 문서에 대한 업로드 요청을 저장할 수 있다")
    void linksUploadToExistingDocumentInSameWorkspace() {
        Document document = new Document("기존 문서", 0, requester, null, workspace);
        entityManager.persist(document);
        DocumentUpload upload = new DocumentUpload(workspace, document, requester, null, 0L, expiresAt);
        entityManager.persist(upload);
        entityManager.flush();
        Long id = upload.getId();
        Long documentId = document.getId();
        entityManager.clear();

        assertThat(entityManager.find(DocumentUpload.class, id).getDocument().getId()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("동일한 S3 저장 키를 파일 정보에 중복 등록할 수 없다")
    void rejectsDuplicateObjectKeyInDatabase() {
        String key = "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID();
        entityManager.persist(file(workspace, key));
        entityManager.flush();
        StoreFile duplicate = file(workspace, key);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("uk_store_file_object_key");
    }

    @Test
    @DisplayName("다른 팀의 원본 파일을 업로드 요청에 연결할 수 없다")
    void rejectsOriginalFileFromAnotherWorkspace() {
        Workspace other = new Workspace("다른 팀", requester);
        entityManager.persist(other);
        StoreFile original = file(other, "workspaces/" + other.getId() + "/uploads/" + UUID.randomUUID());

        assertThatThrownBy(() -> new DocumentUpload(workspace, null, requester, original, 1024L, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 워크스페이스");
    }

    @Test
    @DisplayName("다른 팀의 문서를 업로드 요청에 연결할 수 없다")
    void rejectsDocumentFromAnotherWorkspace() {
        Workspace other = new Workspace("다른 팀", requester);
        entityManager.persist(other);
        Document document = new Document("다른 팀 문서", 0, requester, null, other);

        assertThatThrownBy(() -> new DocumentUpload(workspace, document, requester, null, 0L, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 워크스페이스");
    }

    private StoreFile file(Workspace owner, String key) {
        return new StoreFile(owner, key, "기획서.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
    }
}
