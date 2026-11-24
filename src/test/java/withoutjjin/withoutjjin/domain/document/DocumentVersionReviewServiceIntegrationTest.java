package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.dto.DocumentVersionReviewResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionDeletionService;
import withoutjjin.withoutjjin.domain.document.service.DocumentVersionReviewService;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

// 서로 다른 트랜잭션의 커밋·롤백·경합을 검증하므로 테스트 메서드에 @Transactional을 붙이지 않는다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentVersionReviewServiceIntegrationTest {

    @Autowired private DocumentVersionReviewService service;
    @Autowired private DocumentVersionDeletionService deletion;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private S3Client s3Client;
    private DocumentVersionReviewFixture f;

    @BeforeEach
    void setUp() {
        f = new DocumentVersionReviewFixture(em);
        transaction().executeWithoutResult(status -> f.setUp());
    }

    @AfterEach
    void cleanUpOnlyOwnFixtures() {
        verifyNoInteractions(s3Client);
        f.cleanUp(jdbc);
    }

    @Test
    @DisplayName("완료 기록이 커밋되고 재요청과 별도 조회에서 같은 ID·완료 시각을 반환한다")
    void commitsAndPreservesCompletionAcrossTransactions() {
        DocumentVersionReviewResponse saved = complete(f.first.id(), f.member);
        DocumentVersionReviewResponse repeated = complete(f.first.id(), f.member);
        List<DocumentVersionReviewResponse> found = service.getReviews(f.workspace.getId(), f.document.getId(), f.first.id(),
                f.uploader.getIssuer(), f.uploader.getSubject());

        assertThat(repeated).isEqualTo(saved);
        assertThat(found).containsExactly(saved);
        assertThat(count(f.first.id())).isEqualTo(1);
        assertThat(count(f.second.id())).isZero();
    }

    @Test
    @DisplayName("같은 회원의 동시 요청은 모두 같은 완료 기록을 반환하고 DB에는 한 행만 남긴다")
    void concurrentSameReviewerIsIdempotent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<DocumentVersionReviewResponse> first = executor.submit(() -> completeAfter(start, f.member));
            Future<DocumentVersionReviewResponse> second = executor.submit(() -> completeAfter(start, f.member));
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(count(f.first.id())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("서로 다른 회원의 동시 요청은 각 회원의 완료 기록을 보존한다")
    void concurrentDifferentReviewersBothComplete() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<DocumentVersionReviewResponse> first = executor.submit(() -> completeAfter(start, f.member));
            Future<DocumentVersionReviewResponse> second = executor.submit(() -> completeAfter(start, f.anotherMember));
            assertThat(List.of(first.get(30, TimeUnit.SECONDS).reviewerId(), second.get(30, TimeUnit.SECONDS).reviewerId()))
                    .containsExactlyInAnyOrder(f.member.getId(), f.anotherMember.getId());
            assertThat(count(f.first.id())).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("완료 등록 트랜잭션이 롤백되면 완료 기록과 버전 변경이 남지 않는다")
    void rollsBackCompletion() {
        transaction().executeWithoutResult(status -> {
            complete(f.first.id(), f.member);
            status.setRollbackOnly();
        });
        assertThat(count(f.first.id())).isZero();
        assertThat(jdbc.queryForObject("select page_count from document_version where id = ?", Integer.class, f.first.id()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("선택한 버전 삭제 시 완료 기록만 함께 삭제하고 다른 버전의 완료 기록은 유지한다")
    void deletesOnlySelectedVersionsCompletions() {
        complete(f.first.id(), f.member);
        complete(f.first.id(), f.anotherMember);
        DocumentVersionReviewResponse preserved = complete(f.second.id(), f.member);

        deleteFirst();

        assertThat(count(f.first.id())).isZero();
        assertThat(service.getReviews(f.workspace.getId(), f.document.getId(), f.second.id(),
                f.member.getIssuer(), f.member.getSubject())).containsExactly(preserved);
        assertThat(jdbc.queryForObject("select count(*) from document_version where id = ?", Long.class, f.first.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key in (?, ?)", Long.class,
                f.first.original().getObjectKey(), f.first.pdf().getObjectKey())).isEqualTo(2);
    }

    @Test
    @DisplayName("버전 삭제가 롤백되면 완료 기록·버전·파일 연결도 복원되고 S3 삭제 작업이 남지 않는다")
    void deletionRollbackPreservesReviewRecords() {
        DocumentVersionReviewResponse saved = complete(f.first.id(), f.member);

        transaction().executeWithoutResult(status -> {
            deleteFirst();
            status.setRollbackOnly();
        });

        assertThat(service.getReviews(f.workspace.getId(), f.document.getId(), f.first.id(),
                f.member.getIssuer(), f.member.getSubject())).containsExactly(saved);
        assertThat(jdbc.queryForObject("select review_pdf_file_id from document_upload where id = ?", Long.class,
                f.first.upload().getId())).isEqualTo(f.first.pdf().getId());
        assertThat(jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key in (?, ?)", Long.class,
                f.first.original().getObjectKey(), f.first.pdf().getObjectKey())).isZero();
    }

    @Test
    @DisplayName("완료 등록과 버전 삭제가 동시에 들어와도 삭제 후 완료 기록이 남거나 FK 오류가 발생하지 않는다")
    void coordinatesCompletionWithConcurrentDeletion() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Integer> completed = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    complete(f.first.id(), f.member);
                    return 200;
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });
            Future<?> deleted = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                deleteFirst();
                return null;
            });
            assertThat(completed.get(30, TimeUnit.SECONDS)).isIn(200, 404);
            deleted.get(30, TimeUnit.SECONDS);
            assertThat(count(f.first.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from document_version where id = ?", Long.class, f.first.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from document_version where id = ?", Long.class, f.second.id())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsDeletedVersionAndInvalidServiceIds() {
        deleteFirst();
        assertThatThrownBy(() -> complete(f.first.id(), f.member)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertBadRequest(() -> service.completeReview(invalid, f.document.getId(), f.second.id(), f.member.getIssuer(), f.member.getSubject()));
            assertBadRequest(() -> service.completeReview(f.workspace.getId(), invalid, f.second.id(), f.member.getIssuer(), f.member.getSubject()));
            assertBadRequest(() -> complete(invalid, f.member));
            assertBadRequest(() -> service.getReviews(invalid, f.document.getId(), f.second.id(), f.member.getIssuer(), f.member.getSubject()));
            assertBadRequest(() -> service.getReviews(f.workspace.getId(), invalid, f.second.id(), f.member.getIssuer(), f.member.getSubject()));
            assertBadRequest(() -> service.getReviews(f.workspace.getId(), f.document.getId(), invalid, f.member.getIssuer(), f.member.getSubject()));
        }
        assertThat(count(f.second.id())).isZero();
    }

    private DocumentVersionReviewResponse completeAfter(CyclicBarrier start, AppUser reviewer) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return complete(f.first.id(), reviewer);
    }
    private DocumentVersionReviewResponse complete(Long versionId, AppUser reviewer) {
        return service.completeReview(f.workspace.getId(), f.document.getId(), versionId, reviewer.getIssuer(), reviewer.getSubject());
    }
    private void deleteFirst() {
        deletion.deleteVersion(f.workspace.getId(), f.document.getId(), f.first.id(), f.uploader.getIssuer(), f.uploader.getSubject());
    }
    private long count(Long versionId) {
        return jdbc.queryForObject("select count(*) from document_version_review where document_version_id = ?", Long.class, versionId);
    }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private static void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }
}
