package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackListItemResponse;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.service.DocumentFeedbackService;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFeedbackQueryServiceIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private DocumentFeedbackService service;

    private AppUser author;
    private AppUser member;
    private Document document;

    @BeforeEach
    void setUp() {
        author = user("피드백 작성자");
        member = user("같은 팀의 다른 멤버");
        Workspace workspace = new Workspace("피드백 조회 서비스 테스트팀", author);
        entityManager.persist(workspace);
        entityManager.persist(new WorkspaceMember(workspace, author));
        entityManager.persist(new WorkspaceMember(workspace, member));
        document = new Document("기획서", 2, author, null, workspace);
        entityManager.persist(document);
    }

    @Test
    @DisplayName("작성자가 아닌 워크스페이스 멤버도 이전 DocumentVersion의 피드백을 조회한다")
    void memberCanReadFeedbackForAnOlderVersion() {
        DocumentVersion version = version(1, 2);
        DocumentFeedback feedback = feedback(version);
        Long expectedId = feedback.getId();
        Long authorId = author.getId();
        entityManager.flush();
        entityManager.clear();

        List<DocumentFeedbackListItemResponse> responses = getFeedbacks(version.getId(), member);
        entityManager.clear();

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(expectedId);
            assertThat(response.documentVersionId()).isEqualTo(version.getId());
            assertThat(response.createdBy()).isEqualTo(authorId);
            assertThat(response.createdByDisplayName()).isEqualTo("피드백 작성자");
            assertThat(response.pageNumber()).isEqualTo(2);
            assertThat(response.color()).isEqualTo("blue");
            assertThat(response.content()).isEqualTo("문장을 수정해 주세요.");
            assertThat(response.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("서비스 조회에도 다른 DocumentVersion의 피드백이 섞이지 않는다")
    void returnsOnlyRequestedVersionFeedback() {
        DocumentVersion first = version(1, 2);
        DocumentVersion second = version(2, 2);
        Long expectedId = feedback(first).getId();
        feedback(second);
        entityManager.flush();
        entityManager.clear();

        assertThat(getFeedbacks(first.getId(), member))
                .extracting(DocumentFeedbackListItemResponse::id).containsExactly(expectedId);
    }

    @Test
    @DisplayName("멤버는 PDF 변환 전 피드백이 없는 DocumentVersion도 빈 목록으로 조회한다")
    void returnsEmptyListBeforeConversion() {
        DocumentVersion version = version(1, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(getFeedbacks(version.getId(), member)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("워크스페이스 비멤버는 피드백 유무와 관계없이 403이다")
    void rejectsNonMemberEvenWhenNoFeedbackExists(boolean hasFeedback) {
        DocumentVersion version = version(1, 2);
        if (hasFeedback) feedback(version);
        AppUser outsider = user("다른 팀 회원");
        // 비멤버가 다른 워크스페이스에 소속되어 있어도 접근할 수 없다.
        Workspace otherWorkspace = new Workspace("다른 팀", outsider);
        entityManager.persist(otherWorkspace);
        entityManager.persist(new WorkspaceMember(otherWorkspace, outsider));
        entityManager.flush();
        entityManager.clear();

        assertFailure(HttpStatus.FORBIDDEN, () -> getFeedbacks(version.getId(), outsider));
    }

    @Test
    @DisplayName("존재하지 않는 DocumentVersion은 빈 목록 대신 404다")
    void rejectsMissingVersion() {
        assertFailure(HttpStatus.NOT_FOUND, () -> getFeedbacks(Long.MAX_VALUE, member));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("null 또는 0 이하의 DocumentVersion ID는 400이다")
    void rejectsInvalidVersionId(Long versionId) {
        assertFailure(HttpStatus.BAD_REQUEST, () -> getFeedbacks(versionId, member));
    }

    private List<DocumentFeedbackListItemResponse> getFeedbacks(Long versionId, AppUser requester) {
        return service.getFeedbacks(versionId, requester.getIssuer(), requester.getSubject());
    }

    private void assertFailure(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-query-service@example.com", true, displayName);
        entityManager.persist(user);
        return user;
    }

    private DocumentVersion version(int number, Integer pageCount) {
        DocumentVersion version = new DocumentVersion(number, "", pageCount, document,
                "test/" + UUID.randomUUID(), "기획서.docx", 1024L, author);
        entityManager.persist(version);
        return version;
    }

    private DocumentFeedback feedback(DocumentVersion version) {
        DocumentFeedback feedback = new DocumentFeedback(version, author, 2,
                .1, .2, .3, .4, FeedbackColor.BLUE, "문장을 수정해 주세요.");
        entityManager.persist(feedback);
        return feedback;
    }
}
