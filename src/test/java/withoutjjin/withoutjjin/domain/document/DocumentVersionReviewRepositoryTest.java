package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersionReview;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionReviewRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentVersionReviewRepositoryTest {

    @Autowired private EntityManager em;
    @Autowired private DocumentVersionReviewRepository reviews;
    @MockitoBean private S3Client s3Client;

    private AppUser uploader;
    private AppUser reviewer;
    private Document document;

    @BeforeEach
    void setUp() {
        uploader = user("업로더");
        reviewer = user("피드백 완료 회원");
        Workspace workspace = new Workspace("버전별 피드백 완료", uploader);
        em.persist(workspace);
        document = new Document("이력서", 2, uploader, null, workspace);
        em.persist(document);
    }

    @Test
    @DisplayName("피드백을 완료한 버전·회원·시각을 저장하고 다시 조회한다")
    void persistsCompletedVersionReviewerAndTime() {
        DocumentVersion version = version(1);
        Long versionId = version.getId();
        Long reviewerId = reviewer.getId();
        DocumentVersionReview saved = reviews.saveAndFlush(new DocumentVersionReview(version, reviewer));
        Long reviewId = saved.getId();
        em.clear();

        DocumentVersionReview found = reviews.findByDocumentVersionIdAndReviewerId(versionId, reviewerId).orElseThrow();

        assertThat(found.getId()).isEqualTo(reviewId);
        assertThat(found.getDocumentVersion().getId()).isEqualTo(versionId);
        assertThat(found.getReviewer().getId()).isEqualTo(reviewerId);
        assertThat(found.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 회원의 같은 버전 완료 기록은 DB 유일 제약으로 중복 저장하지 못한다")
    void rejectsDuplicateCompletionForSameVersionAndReviewer() {
        DocumentVersion version = version(1);
        reviews.saveAndFlush(new DocumentVersionReview(version, reviewer));

        assertThatThrownBy(() -> reviews.saveAndFlush(new DocumentVersionReview(version, reviewer)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("여러 회원이 같은 버전의 피드백을 각각 완료할 수 있다")
    void keepsEachReviewersCompletionForSameVersion() {
        DocumentVersion version = version(1);
        Long versionId = version.getId();
        AppUser another = user("다른 완료 회원");
        Long firstReviewerId = reviewer.getId();
        Long secondReviewerId = another.getId();
        reviews.saveAndFlush(new DocumentVersionReview(version, reviewer));
        reviews.saveAndFlush(new DocumentVersionReview(version, another));
        em.clear();

        assertThat(reviews.findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(versionId))
                .extracting(review -> review.getReviewer().getId())
                .containsExactly(firstReviewerId, secondReviewerId);
    }

    @Test
    @DisplayName("같은 회원이 V1과 V2를 각각 완료하며 다른 버전의 목록은 섞이지 않는다")
    void keepsCompletionsIndependentAcrossVersions() {
        DocumentVersion first = version(1);
        DocumentVersion second = version(2);
        Long firstId = first.getId();
        Long secondId = second.getId();
        DocumentVersionReview firstReview = reviews.saveAndFlush(new DocumentVersionReview(first, reviewer));
        DocumentVersionReview secondReview = reviews.saveAndFlush(new DocumentVersionReview(second, reviewer));
        Long firstReviewId = firstReview.getId();
        Long secondReviewId = secondReview.getId();
        em.clear();

        assertThat(reviews.findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(firstId))
                .extracting(DocumentVersionReview::getId).containsExactly(firstReviewId);
        assertThat(reviews.findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(secondId))
                .extracting(DocumentVersionReview::getId).containsExactly(secondReviewId);
    }

    @Test
    @DisplayName("완료한 회원이 없는 버전은 빈 목록을 반환한다")
    void returnsEmptyForVersionWithoutCompletedReview() {
        DocumentVersion version = version(1);
        em.flush();

        assertThat(reviews.findAllByDocumentVersionIdOrderByCompletedAtAscIdAsc(version.getId())).isEmpty();
        assertThat(reviews.findByDocumentVersionIdAndReviewerId(version.getId(), reviewer.getId())).isEmpty();
    }

    private AppUser user(String name) {
        String subject = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://accounts.google.com", subject,
                subject + "@example.invalid", true, name);
        em.persist(user);
        return user;
    }

    private DocumentVersion version(int number) {
        DocumentVersion version = new DocumentVersion(number, "", 2, document,
                "workspaces/1/uploads/" + UUID.randomUUID(), "이력서.docx", 1024L, uploader);
        em.persist(version);
        return version;
    }
}
