package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackResponse;
import withoutjjin.withoutjjin.domain.feedback.dto.UpdateDocumentFeedbackRequest;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.feedback.service.DocumentFeedbackService;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFeedbackMutationServiceIntegrationTest {

    @Autowired private EntityManager em;
    @Autowired private DocumentFeedbackService service;
    @Autowired private DocumentFeedbackRepository feedbacks;

    private AppUser author;
    private AppUser member;
    private Workspace workspace;
    private Document document;
    private DocumentVersion version;
    private Long feedbackId;

    enum Operation { UPDATE, DELETE }

    @BeforeEach
    void setUp() {
        author = user("작성자");
        member = user("다른 멤버");
        workspace = new Workspace("피드백 수정·삭제 테스트팀", author);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, author));
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("기획서", 2, author, null, workspace);
        em.persist(document);
        version = new DocumentVersion(1, "", 2, document,
                "test/" + UUID.randomUUID(), "기획서.docx", 1024L, author);
        em.persist(version);
        DocumentFeedback feedback = new DocumentFeedback(version, author, 2,
                .1, .2, .3, .4, FeedbackColor.PINK, "기존 의견");
        em.persist(feedback);
        feedbackId = feedback.getId();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("작성자의 수정 내용을 DB에 반영하고 문서 버전·작성자·영역·색상·작성 시각을 유지한다")
    void updatesOnlyContentAndReadsTheChangeFromDatabase() {
        LocalDateTime createdAt = feedbacks.findById(feedbackId).orElseThrow().getCreatedAt();
        em.clear();

        DocumentFeedbackResponse response = update("수정한 의견");
        em.flush();
        em.clear();

        DocumentFeedback found = feedbacks.findById(feedbackId).orElseThrow();
        assertThat(found.getContent()).isEqualTo("수정한 의견");
        assertThat(found.getDocumentVersion().getId()).isEqualTo(version.getId());
        assertThat(found.getCreatedBy().getId()).isEqualTo(author.getId());
        assertThat(found.getPageNumber()).isEqualTo(2);
        assertThat(found.getX()).isEqualTo(.1);
        assertThat(found.getY()).isEqualTo(.2);
        assertThat(found.getWidth()).isEqualTo(.3);
        assertThat(found.getHeight()).isEqualTo(.4);
        assertThat(found.getColor()).isEqualTo(FeedbackColor.PINK);
        assertThat(found.getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.id()).isEqualTo(feedbackId);
        assertThat(response.content()).isEqualTo("수정한 의견");
        assertThat(service.getFeedbacks(version.getId(), member.getIssuer(), member.getSubject()))
                .singleElement().satisfies(item -> assertThat(item.content()).isEqualTo("수정한 의견"));
    }

    @Test
    @DisplayName("작성자가 삭제하면 조회 목록에서 사라지고 DocumentVersion·Document·AppUser는 남는다")
    void deletesOnlyTheFeedbackAndExcludesItFromTheList() {
        service.deleteFeedback(feedbackId, author.getIssuer(), author.getSubject());
        em.flush();
        em.clear();

        assertThat(feedbacks.findById(feedbackId)).isEmpty();
        assertThat(service.getFeedbacks(version.getId(), member.getIssuer(), member.getSubject())).isEmpty();
        assertThat(em.find(DocumentVersion.class, version.getId())).isNotNull();
        assertThat(em.find(Document.class, document.getId())).isNotNull();
        assertThat(em.find(AppUser.class, author.getId())).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("같은 워크스페이스 멤버여도 다른 작성자의 피드백 수정·삭제는 403이다")
    void rejectsAnotherMemberWithoutChangingFeedback(Operation operation) {
        assertFailure(HttpStatus.FORBIDDEN, () -> mutate(operation, feedbackId, member));
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("다른 워크스페이스에 소속된 회원도 수정·삭제할 수 없다")
    void rejectsAnOutsiderWithoutChangingFeedback(Operation operation) {
        AppUser outsider = user("다른 팀 회원");
        Workspace other = new Workspace("다른 팀", outsider);
        em.persist(other);
        em.persist(new WorkspaceMember(other, outsider));
        em.flush();
        em.clear();

        assertFailure(HttpStatus.FORBIDDEN, () -> mutate(operation, feedbackId, outsider));
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("작성자여도 워크스페이스에서 탈퇴하면 수정·삭제할 수 없다")
    void rejectsTheAuthorAfterMembershipIsRemoved(Operation operation) {
        em.createQuery("delete from WorkspaceMember m where m.workspace.id = :workspaceId and m.user.id = :userId")
                .setParameter("workspaceId", workspace.getId())
                .setParameter("userId", author.getId()).executeUpdate();
        em.flush();
        em.clear();

        assertFailure(HttpStatus.FORBIDDEN, () -> mutate(operation, feedbackId, author));
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("존재하지 않는 피드백 수정·삭제는 404다")
    void rejectsMissingFeedback(Operation operation) {
        assertFailure(HttpStatus.NOT_FOUND, () -> mutate(operation, Long.MAX_VALUE, author));
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("null 또는 0 이하의 피드백 ID는 400이다")
    void rejectsInvalidFeedbackIds(Operation operation) {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertFailure(HttpStatus.BAD_REQUEST, () -> mutate(operation, id, author));
        }
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u3000"})
    @DisplayName("공백 내용으로 서비스에서 직접 수정해도 400이며 DB의 기존 내용은 유지된다")
    void rejectsBlankContentWithoutChangingFeedback(String content) {
        assertFailure(HttpStatus.BAD_REQUEST, () -> update(content));
        assertOriginalFeedback();
    }

    @Test
    @DisplayName("1000자 초과 내용과 null 수정 요청은 400이며 기존 내용은 유지된다")
    void rejectsOversizedContentAndMissingRequest() {
        assertFailure(HttpStatus.BAD_REQUEST, () -> update("가".repeat(1001)));
        assertFailure(HttpStatus.BAD_REQUEST, () -> service.updateFeedback(
                feedbackId, null, author.getIssuer(), author.getSubject()));
        assertOriginalFeedback();
    }

    @Test
    @DisplayName("1000자 수정은 허용한다")
    void acceptsMaximumContentLength() {
        DocumentFeedbackResponse response = update("가".repeat(1000));
        em.flush();
        em.clear();
        assertThat(response.content()).hasSize(1000);
        assertThat(feedbacks.findById(feedbackId).orElseThrow().getContent()).hasSize(1000);
    }

    private DocumentFeedbackResponse update(String content) {
        return service.updateFeedback(feedbackId, new UpdateDocumentFeedbackRequest(content),
                author.getIssuer(), author.getSubject());
    }

    private void mutate(Operation operation, Long id, AppUser requester) {
        if (operation == Operation.UPDATE) {
            service.updateFeedback(id, new UpdateDocumentFeedbackRequest("변경 시도"),
                    requester.getIssuer(), requester.getSubject());
        } else {
            service.deleteFeedback(id, requester.getIssuer(), requester.getSubject());
        }
    }

    private void assertOriginalFeedback() {
        em.flush();
        em.clear();
        assertThat(feedbacks.findById(feedbackId)).hasValueSatisfying(feedback ->
                assertThat(feedback.getContent()).isEqualTo("기존 의견"));
    }

    private void assertFailure(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-mutation@example.com", true, displayName);
        em.persist(user);
        return user;
    }
}
