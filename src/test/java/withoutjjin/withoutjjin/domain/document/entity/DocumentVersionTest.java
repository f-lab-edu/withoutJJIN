package withoutjjin.withoutjjin.domain.document.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentVersionTest {

    private final AppUser requester = new AppUser(
            "https://accounts.google.com", "page-count-test", "test@example.com", true, "테스트 회원");
    private final Workspace workspace = new Workspace("페이지 수 테스트팀", requester);
    private final Document document = new Document("기획서", 1, requester, null, workspace);

    @Test
    @DisplayName("변환 전 페이지 수가 없는 DocumentVersion에 PDF 페이지 수를 기록한다")
    void recordsPageCountWithoutChangingOriginalMetadata() {
        DocumentVersion version = version(null);

        version.recordPageCount(3);

        assertThat(version.getPageCount()).isEqualTo(3);
        assertThat(version.getStorageKey()).isEqualTo("workspaces/10/uploads/source");
        assertThat(version.getFileSize()).isEqualTo(1024L);
        assertThat(version.getOriginalFileName()).isEqualTo("기획서.docx");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    @DisplayName("페이지 수가 없거나 양수가 아니면 DocumentVersion을 변경하지 않는다")
    void rejectsMissingOrNonPositivePageCount(Integer pageCount) {
        DocumentVersion version = version(null);

        assertThatThrownBy(() -> version.recordPageCount(pageCount))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 수");

        assertThat(version.getPageCount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    @DisplayName("이미 기록된 페이지 수를 다시 기록하지 않는다")
    void rejectsRepeatedPageCountRecording(int pageCount) {
        DocumentVersion version = version(3);

        assertThatThrownBy(() -> version.recordPageCount(pageCount))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미");

        assertThat(version.getPageCount()).isEqualTo(3);
    }

    private DocumentVersion version(Integer pageCount) {
        return new DocumentVersion(1, "", pageCount, document,
                "workspaces/10/uploads/source", "기획서.docx", 1024L, requester);
    }
}
