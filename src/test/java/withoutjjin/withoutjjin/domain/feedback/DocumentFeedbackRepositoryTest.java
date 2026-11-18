package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackListItemResponse;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFeedbackRepositoryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private DocumentFeedbackRepository feedbacks;

    private AppUser author;
    private Document document;

    @BeforeEach
    void setUp() {
        author = user("피드백 작성자");
        Workspace workspace = new Workspace("피드백 조회 테스트팀", author);
        entityManager.persist(workspace);
        document = new Document("기획서", 2, author, null, workspace);
        entityManager.persist(document);
    }

    @Test
    @DisplayName("같은 Document의 다른 DocumentVersion 피드백을 섞지 않는다")
    void filtersByExactDocumentVersion() {
        DocumentVersion firstVersion = version(1);
        DocumentVersion secondVersion = version(2);
        Long expectedId = feedback(firstVersion, author, "V1 피드백").getId();
        feedback(secondVersion, author, "V2 피드백");
        entityManager.flush();
        entityManager.clear();

        assertThat(feedbacks.findAllByDocumentVersionId(firstVersion.getId()))
                .extracting(DocumentFeedback::getId).containsExactly(expectedId);
    }

    @Test
    @DisplayName("피드백이 없는 DocumentVersion은 빈 목록을 반환한다")
    void returnsEmptyList() {
        DocumentVersion emptyVersion = version(1);
        feedback(version(2), author, "다른 버전의 피드백");
        entityManager.flush();
        entityManager.clear();

        assertThat(feedbacks.findAllByDocumentVersionId(emptyVersion.getId())).isEmpty();
    }

    @Test
    @DisplayName("작성 시각 오름차순으로 정렬하고 같은 시각에는 ID 오름차순으로 정렬한다")
    void ordersByCreatedAtThenId() {
        DocumentVersion version = version(1);
        DocumentFeedback later = feedback(version, author, "나중 시각");
        DocumentFeedback first = feedback(version, author, "같은 시각의 작은 ID");
        DocumentFeedback second = feedback(version, author, "같은 시각의 큰 ID");
        entityManager.flush();
        // 생성 순서와 시간 순서를 다르게 만들어 쿼리의 두 정렬 기준을 검증한다.
        setCreatedAt(later.getId(), LocalDateTime.of(2030, 1, 2, 12, 0));
        setCreatedAt(first.getId(), LocalDateTime.of(2030, 1, 1, 12, 0));
        setCreatedAt(second.getId(), LocalDateTime.of(2030, 1, 1, 12, 0));
        entityManager.clear();

        assertThat(feedbacks.findAllByDocumentVersionId(version.getId()))
                .extracting(DocumentFeedback::getId)
                .containsExactly(first.getId(), second.getId(), later.getId());
    }

    @Test
    @DisplayName("작성자 AppUser를 함께 조회하고 응답 DTO에 영역과 작성자 표시 이름을 담는다")
    void fetchesAuthorsAndMapsResponseWithoutLazyLoading() {
        DocumentVersion version = version(1);
        AppUser anotherAuthor = user(null);
        DocumentFeedback saved = feedback(version, author, "문장을 수정해 주세요.");
        feedback(version, anotherAuthor, "다른 작성자");
        Long authorId = author.getId();
        entityManager.flush();
        entityManager.clear();

        var results = feedbacks.findAllByDocumentVersionId(version.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result ->
                assertThat(Hibernate.isInitialized(result.getCreatedBy())).isTrue());
        entityManager.clear();
        // 조회된 AppUser가 분리된 상태에서도 표시 이름을 읽을 수 있어야 한다.
        var responses = results.stream().map(DocumentFeedbackListItemResponse::from).toList();
        DocumentFeedbackListItemResponse response = responses.stream()
                .filter(item -> item.id().equals(saved.getId())).findFirst().orElseThrow();
        assertThat(response.documentVersionId()).isEqualTo(version.getId());
        assertThat(response.createdBy()).isEqualTo(authorId);
        assertThat(response.createdByDisplayName()).isEqualTo("피드백 작성자");
        assertThat(response.pageNumber()).isEqualTo(2);
        assertThat(response.x()).isEqualTo(.1);
        assertThat(response.y()).isEqualTo(.2);
        assertThat(response.width()).isEqualTo(.3);
        assertThat(response.height()).isEqualTo(.4);
        assertThat(response.color()).isEqualTo("blue");
        assertThat(response.content()).isEqualTo("문장을 수정해 주세요.");
        assertThat(response.createdAt()).isNotNull();
        assertThat(responses).filteredOn(item -> item.createdBy().equals(anotherAuthor.getId()))
                .singleElement().satisfies(item -> assertThat(item.createdByDisplayName()).isNull());
    }

    private void setCreatedAt(Long id, LocalDateTime createdAt) {
        entityManager.createNativeQuery("update document_feedback set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", id).executeUpdate();
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-query@example.com", true, displayName);
        entityManager.persist(user);
        return user;
    }

    private DocumentVersion version(int number) {
        DocumentVersion version = new DocumentVersion(number, "", 2, document,
                "test/" + UUID.randomUUID(), "기획서.docx", 1024L, author);
        entityManager.persist(version);
        return version;
    }

    private DocumentFeedback feedback(DocumentVersion version, AppUser createdBy, String content) {
        DocumentFeedback feedback = new DocumentFeedback(version, createdBy, 2,
                .1, .2, .3, .4, FeedbackColor.BLUE, content);
        entityManager.persist(feedback);
        return feedback;
    }
}
