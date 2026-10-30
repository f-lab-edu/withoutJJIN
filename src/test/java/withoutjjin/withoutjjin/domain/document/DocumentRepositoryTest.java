package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentRepositoryTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private EntityManager entityManager;

    private AppUser creator;
    private AppUser uploader;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        creator = user("문서 생성자");
        uploader = user("버전 업로더");
        workspace = workspaceRepository.save(new Workspace("문서 테스트팀", creator));
    }

    @Test
    @DisplayName("저장소로 문서와 버전을 저장하고 파일 정보 및 회원 관계를 DB에서 다시 조회한다")
    void savesAndReloadsDocumentVersionsWithFileMetadataAndRelationships() {
        Document document = document("기획서");
        DocumentVersion first = documentVersionRepository.save(version(document, 1, null));
        DocumentVersion second = documentVersionRepository.save(version(document, 2, 8));
        document.updateRevision(2);
        documentRepository.flush();

        Long documentId = document.getId();
        Long firstId = first.getId();
        Long secondId = second.getId();
        Long creatorId = creator.getId();
        Long uploaderId = uploader.getId();
        Long workspaceId = workspace.getId();
        // 관리 중인 객체가 아닌, DB에서 다시 읽은 값으로 검증한다.
        entityManager.clear();

        Document found = documentRepository.findById(documentId).orElseThrow();
        DocumentVersion foundFirst = documentVersionRepository.findById(firstId).orElseThrow();
        DocumentVersion foundSecond = documentVersionRepository.findById(secondId).orElseThrow();

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
        documentVersionRepository.saveAndFlush(version(document, 1, 3));

        assertThatThrownBy(() -> documentVersionRepository.saveAndFlush(version(document, 1, 4)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_document_version_document_id_version_number");
    }

    @Test
    @DisplayName("서로 다른 문서는 같은 버전 번호를 사용할 수 있다")
    void allowsSameVersionNumberForDifferentDocuments() {
        Document firstDocument = document("첫 문서");
        Document secondDocument = document("두 번째 문서");
        DocumentVersion first = documentVersionRepository.save(version(firstDocument, 1, 2));
        DocumentVersion second = documentVersionRepository.saveAndFlush(version(secondDocument, 1, 3));
        Long firstId = first.getId();
        Long secondId = second.getId();
        entityManager.clear();

        DocumentVersion foundFirst = documentVersionRepository.findById(firstId).orElseThrow();
        DocumentVersion foundSecond = documentVersionRepository.findById(secondId).orElseThrow();
        assertThat(foundFirst.getVersionNumber()).isEqualTo(1);
        assertThat(foundSecond.getVersionNumber()).isEqualTo(1);
        assertThat(foundFirst.getDocument().getId()).isNotEqualTo(foundSecond.getDocument().getId());
    }

    @Test
    @DisplayName("선택한 문서의 버전만 버전 번호 내림차순으로 조회한다")
    void listsOnlyRequestedDocumentVersionsInDescendingOrder() {
        Document selected = document("선택한 문서");
        Document other = document("다른 문서");
        // 저장 순서나 ID 순서가 아닌 버전 번호로 정렬하는지 확인한다.
        DocumentVersion second = documentVersionRepository.save(version(selected, 2, 4));
        DocumentVersion first = documentVersionRepository.save(version(selected, 1, 2));
        DocumentVersion third = documentVersionRepository.save(version(selected, 3, 6));
        DocumentVersion otherVersion = documentVersionRepository.saveAndFlush(version(other, 9, 1));
        Long selectedId = selected.getId();
        List<Long> expectedIds = List.of(third.getId(), second.getId(), first.getId());
        Long otherVersionId = otherVersion.getId();
        entityManager.clear();

        List<DocumentVersion> versions = documentVersionRepository
                .findAllByDocumentIdOrderByVersionNumberDesc(selectedId);

        assertThat(versions).extracting(DocumentVersion::getId).containsExactlyElementsOf(expectedIds);
        assertThat(versions).extracting(DocumentVersion::getVersionNumber).containsExactly(3, 2, 1);
        assertThat(versions).allSatisfy(version ->
                assertThat(version.getDocument().getId()).isEqualTo(selectedId));
        assertThat(versions).extracting(DocumentVersion::getId).doesNotContain(otherVersionId);
    }

    @Test
    @DisplayName("다른 문서에 버전이 있어도 버전이 없는 문서는 빈 목록을 반환한다")
    void returnsEmptyListForDocumentWithoutVersions() {
        Document empty = document("아직 파일이 없는 문서");
        Document other = document("파일이 있는 문서");
        documentVersionRepository.saveAndFlush(version(other, 1, 2));
        Long emptyId = empty.getId();
        entityManager.clear();

        assertThat(documentVersionRepository.findAllByDocumentIdOrderByVersionNumberDesc(emptyId))
                .isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 문서의 버전 조회는 빈 목록을 반환한다")
    void returnsEmptyListForMissingDocument() {
        Document removed = document("삭제할 빈 문서");
        Long missingId = removed.getId();
        Document other = document("다른 문서");
        documentVersionRepository.saveAndFlush(version(other, 1, 2));
        documentRepository.delete(removed);
        documentRepository.flush();
        entityManager.clear();

        assertThat(documentRepository.findById(missingId)).isEmpty();
        assertThat(documentVersionRepository.findAllByDocumentIdOrderByVersionNumberDesc(missingId))
                .isEmpty();
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
        return appUserRepository.save(new AppUser("https://accounts.google.com", subject,
                subject + "@example.com", true, displayName));
    }

    private Document document(String title) {
        return documentRepository.save(new Document(title, 0, creator, null, workspace));
    }

    private DocumentVersion version(Document document, int number, Integer pageCount) {
        return new DocumentVersion(number, "버전 " + number + " 수정 내용", pageCount,
                document, "documents/" + document.getId() + "/v" + number + ".pdf",
                "plan-v" + number + ".pdf", 2048L, uploader);
    }
}
