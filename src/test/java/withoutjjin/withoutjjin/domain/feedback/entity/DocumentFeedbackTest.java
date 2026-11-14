package withoutjjin.withoutjjin.domain.feedback.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFeedbackTest {

    private final AppUser author = new AppUser("https://accounts.google.com",
            "feedback-author", "author@example.com", true, "작성자");
    private final Workspace workspace = new Workspace("피드백 테스트팀", author);
    private final Document document = new Document("기획서", 2, author, null, workspace);

    @ParameterizedTest
    @EnumSource(FeedbackColor.class)
    @DisplayName("선택한 DocumentVersion과 작성자, 영역, 색상, 내용을 보관한다")
    void createsFeedbackForTheSpecifiedVersion(FeedbackColor color) {
        DocumentVersion version = version(2);

        DocumentFeedback feedback = new DocumentFeedback(
                version, author, 2, .1, .2, .3, .4, color, "이 문장을 수정해 주세요.");

        assertThat(feedback.getDocumentVersion()).isSameAs(version);
        // Document.revision이 2여도 피드백 대상은 전달한 1번 버전이다.
        assertThat(feedback.getDocumentVersion().getVersionNumber()).isEqualTo(1);
        assertThat(feedback.getCreatedBy()).isSameAs(author);
        assertThat(feedback.getPageNumber()).isEqualTo(2);
        assertThat(feedback.getX()).isEqualTo(.1);
        assertThat(feedback.getY()).isEqualTo(.2);
        assertThat(feedback.getWidth()).isEqualTo(.3);
        assertThat(feedback.getHeight()).isEqualTo(.4);
        assertThat(feedback.getColor()).isEqualTo(color);
        assertThat(feedback.getContent()).isEqualTo("이 문장을 수정해 주세요.");
    }

    @Test
    @DisplayName("페이지 전체 영역과 최대 길이 내용을 허용한다")
    void allowsFullPageSelectionAndMaximumContentLength() {
        DocumentFeedback feedback = new DocumentFeedback(
                version(1), author, 1, 0d, 0d, 1d, 1d, FeedbackColor.YELLOW, "가".repeat(1000));

        assertThat(feedback.getWidth()).isEqualTo(1d);
        assertThat(feedback.getHeight()).isEqualTo(1d);
        assertThat(feedback.getContent()).hasSize(1000);
    }

    @Test
    @DisplayName("DocumentVersion 또는 작성자가 없으면 거부한다")
    void rejectsMissingVersionOrAuthor() {
        assertThatThrownBy(() -> new DocumentFeedback(null, author, 1,
                0d, 0d, .2, .2, FeedbackColor.GREEN, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DocumentVersion");
        assertThatThrownBy(() -> new DocumentFeedback(version(2), null, 1,
                0d, 0d, .2, .2, FeedbackColor.GREEN, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AppUser");
    }

    @Test
    @DisplayName("PDF 페이지 수가 확인되지 않은 DocumentVersion은 거부한다")
    void rejectsVersionWithoutPageCount() {
        assertThatThrownBy(() -> new DocumentFeedback(version(null), author, 1,
                0d, 0d, .2, .2, FeedbackColor.GREEN, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 수");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, 3})
    @DisplayName("존재하지 않는 페이지 번호는 거부한다")
    void rejectsInvalidPageNumber(Integer pageNumber) {
        assertThatThrownBy(() -> new DocumentFeedback(version(2), author, pageNumber,
                0d, 0d, .2, .2, FeedbackColor.GREEN, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 번호");
    }

    @ParameterizedTest
    @MethodSource("invalidRegions")
    @DisplayName("숫자가 아니거나 크기가 없거나 페이지 경계를 벗어난 영역은 거부한다")
    void rejectsInvalidRegion(Double x, Double y, Double width, Double height) {
        assertThatThrownBy(() -> new DocumentFeedback(version(2), author, 1,
                x, y, width, height, FeedbackColor.GREEN, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("영역");
    }

    static Stream<Arguments> invalidRegions() {
        return Stream.of(
                Arguments.of(null, .1, .2, .2), Arguments.of(.1, null, .2, .2),
                Arguments.of(.1, .1, null, .2), Arguments.of(.1, .1, .2, null),
                Arguments.of(Double.NaN, .1, .2, .2), Arguments.of(.1, Double.POSITIVE_INFINITY, .2, .2),
                Arguments.of(.1, .1, Double.NEGATIVE_INFINITY, .2), Arguments.of(.1, .1, .2, Double.NaN),
                Arguments.of(-.1, .1, .2, .2), Arguments.of(.1, -.1, .2, .2),
                Arguments.of(.1, .1, 0d, .2), Arguments.of(.1, .1, .2, 0d),
                Arguments.of(.1, .1, -.1, .2), Arguments.of(.1, .1, .2, -.1),
                Arguments.of(1d, .1, .2, .2), Arguments.of(.1, 1d, .2, .2),
                Arguments.of(0d, 0d, 1.1, .2), Arguments.of(0d, 0d, .2, 1.1),
                Arguments.of(.8, .1, .3, .2), Arguments.of(.1, .8, .2, .3)
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("피드백 내용이 없거나 공백뿐이면 거부한다")
    void rejectsBlankContent(String content) {
        assertThatThrownBy(() -> new DocumentFeedback(version(2), author, 1,
                0d, 0d, .2, .2, FeedbackColor.GREEN, content))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("피드백 내용");
    }

    @Test
    @DisplayName("1000자를 넘는 피드백 내용은 거부한다")
    void rejectsContentOverLimit() {
        assertThatThrownBy(() -> new DocumentFeedback(version(2), author, 1,
                0d, 0d, .2, .2, FeedbackColor.GREEN, "가".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000자");
    }

    @Test
    @DisplayName("영역 색상이 없으면 거부한다")
    void rejectsMissingColor() {
        assertThatThrownBy(() -> new DocumentFeedback(version(2), author, 1,
                0d, 0d, .2, .2, null, "내용"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("색상");
    }

    @Test
    @DisplayName("내용 수정 시 문서 버전·작성자·페이지·좌표·색상·작성 시각을 유지한다")
    void updatesOnlyTheContent() {
        DocumentVersion version = version(2);
        DocumentFeedback feedback = new DocumentFeedback(version, author, 2,
                .1, .2, .3, .4, FeedbackColor.PINK, "기존 의견");
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 14, 12, 0);
        ReflectionTestUtils.setField(feedback, "createdAt", createdAt);

        feedback.updateContent("  수정한 의견\n두 번째 줄  ");

        assertThat(feedback.getContent()).isEqualTo("  수정한 의견\n두 번째 줄  ");
        assertThat(feedback.getDocumentVersion()).isSameAs(version);
        assertThat(feedback.getCreatedBy()).isSameAs(author);
        assertThat(feedback.getPageNumber()).isEqualTo(2);
        assertThat(feedback.getX()).isEqualTo(.1);
        assertThat(feedback.getY()).isEqualTo(.2);
        assertThat(feedback.getWidth()).isEqualTo(.3);
        assertThat(feedback.getHeight()).isEqualTo(.4);
        assertThat(feedback.getColor()).isEqualTo(FeedbackColor.PINK);
        assertThat(feedback.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("최대 1000자 내용으로 수정할 수 있다")
    void acceptsMaximumLengthWhenUpdating() {
        DocumentFeedback feedback = new DocumentFeedback(version(1), author, 1,
                0d, 0d, 1d, 1d, FeedbackColor.GREEN, "기존 의견");

        feedback.updateContent("가".repeat(DocumentFeedback.MAX_CONTENT_LENGTH));

        assertThat(feedback.getContent()).hasSize(DocumentFeedback.MAX_CONTENT_LENGTH);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u3000"})
    @DisplayName("공백 내용으로 수정하면 예외가 발생하고 기존 내용은 유지된다")
    void preservesContentWhenTheNewContentIsBlank(String content) {
        DocumentFeedback feedback = new DocumentFeedback(version(1), author, 1,
                0d, 0d, 1d, 1d, FeedbackColor.GREEN, "기존 의견");

        assertThatThrownBy(() -> feedback.updateContent(content))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(feedback.getContent()).isEqualTo("기존 의견");
    }

    @Test
    @DisplayName("1000자를 초과하는 내용으로 수정하면 기존 내용은 유지된다")
    void preservesContentWhenTheNewContentIsTooLong() {
        DocumentFeedback feedback = new DocumentFeedback(version(1), author, 1,
                0d, 0d, 1d, 1d, FeedbackColor.GREEN, "기존 의견");

        assertThatThrownBy(() -> feedback.updateContent("가".repeat(DocumentFeedback.MAX_CONTENT_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(feedback.getContent()).isEqualTo("기존 의견");
    }

    private DocumentVersion version(Integer pageCount) {
        return new DocumentVersion(1, "", pageCount, document,
                "workspaces/10/uploads/source", "기획서.docx", 1024L, author);
    }
}
