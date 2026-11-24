package withoutjjin.withoutjjin.domain.document.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentVersionReviewTest {

    private final AppUser uploader = user("uploader");
    private final AppUser reviewer = user("reviewer");
    private final Workspace workspace = new Workspace("피드백 완료 테스트", uploader);
    private final Document document = new Document("이력서", 2, uploader, null, workspace);

    @Test
    @DisplayName("V1의 피드백 완료 기록은 Document와 V2의 정보를 변경하지 않는다")
    void recordsCompletionOnlyForSelectedVersion() {
        DocumentVersion first = version(1, 2);
        DocumentVersion second = version(2, 3);

        DocumentVersionReview review = new DocumentVersionReview(first, reviewer);

        assertThat(review.getDocumentVersion()).isSameAs(first);
        assertThat(review.getReviewer()).isSameAs(reviewer);
        assertThat(document.getApprover()).isNull();
        assertThat(document.getRevision()).isEqualTo(2);
        assertThat(second.getPageCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("버전 또는 완료한 회원이 없으면 피드백 완료 기록을 생성하지 않는다")
    void requiresVersionAndReviewer(boolean missingVersion) {
        assertThatThrownBy(() -> new DocumentVersionReview(
                missingVersion ? null : version(1, 2), missingVersion ? reviewer : null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("필요합니다");
    }

    @Test
    @DisplayName("PDF 페이지 수가 확인되지 않은 버전의 피드백 완료는 거부한다")
    void rejectsVersionWithoutPdfPageCount() {
        DocumentVersion pending = version(1, null);

        assertThatThrownBy(() -> new DocumentVersionReview(pending, reviewer))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 수");
    }

    private DocumentVersion version(int number, Integer pages) {
        return new DocumentVersion(number, "", pages, document,
                "workspaces/1/uploads/resume-" + number, "이력서.docx", 1024L, uploader);
    }

    private static AppUser user(String subject) {
        return new AppUser("https://accounts.google.com", subject,
                subject + "@example.invalid", true, subject);
    }
}
