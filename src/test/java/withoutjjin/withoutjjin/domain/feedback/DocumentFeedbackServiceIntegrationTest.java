package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.feedback.dto.CreateDocumentFeedbackRequest;
import withoutjjin.withoutjjin.domain.feedback.dto.DocumentFeedbackResponse;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.feedback.service.DocumentFeedbackService;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentFeedbackServiceIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private DocumentFeedbackService service;
    @Autowired private DocumentFeedbackRepository feedbacks;

    private AppUser author;
    private Workspace workspace;
    private Document document;

    @BeforeEach
    void setUp() {
        author = user();
        workspace = new Workspace("피드백 서비스 테스트팀", author);
        entityManager.persist(workspace);
        entityManager.persist(new WorkspaceMember(workspace, author));
        document = new Document("기획서", 2, author, null, workspace);
        entityManager.persist(document);
    }

    @Test
    @DisplayName("로그인한 작성자와 지정한 DocumentVersion으로 피드백을 저장하고 DB에서 다시 읽는다")
    void savesFeedbackForTheRequestedVersionAndAuthenticatedAuthor() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        Long versionId = version.getId();
        Long authorId = author.getId();

        DocumentFeedbackResponse response = create(versionId, request(), author);
        entityManager.flush();
        entityManager.clear();

        DocumentFeedback saved = feedbacks.findById(response.id()).orElseThrow();
        assertThat(saved.getDocumentVersion().getId()).isEqualTo(versionId);
        // 최신 Document.revision은 2여도, 보고 있던 V1에 피드백을 등록할 수 있다.
        assertThat(saved.getDocumentVersion().getVersionNumber()).isEqualTo(1);
        assertThat(saved.getCreatedBy().getId()).isEqualTo(authorId);
        assertThat(saved.getPageNumber()).isEqualTo(2);
        assertThat(saved.getX()).isEqualTo(.1);
        assertThat(saved.getY()).isEqualTo(.2);
        assertThat(saved.getWidth()).isEqualTo(.3);
        assertThat(saved.getHeight()).isEqualTo(.4);
        assertThat(saved.getColor()).isEqualTo(FeedbackColor.BLUE);
        assertThat(saved.getContent()).isEqualTo("문장을 수정해 주세요.");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(response.documentVersionId()).isEqualTo(versionId);
        assertThat(response.createdBy()).isEqualTo(authorId);
        // DB 저장 전후 소수초 정밀도 차이를 허용한다.
        assertThat(response.createdAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MICROS));
        assertThat(response.color()).isEqualTo("blue");
    }

    @Test
    @DisplayName("워크스페이스 비멤버는 피드백을 저장할 수 없다")
    void rejectsNonMemberWithoutSavingFeedback() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        AppUser outsider = user();

        assertFailure(HttpStatus.FORBIDDEN, () -> create(version.getId(), request(), outsider));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 문서 버전은 404다")
    void rejectsMissingVersion() {
        assertFailure(HttpStatus.NOT_FOUND, () -> create(Long.MAX_VALUE, request(), author));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("유효하지 않은 문서 버전 ID는 400이다")
    void rejectsInvalidVersionId(Long versionId) {
        assertFailure(HttpStatus.BAD_REQUEST, () -> create(versionId, request(), author));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("PDF 변환이 완료되지 않은 DocumentVersion은 409다")
    void rejectsUnfinishedPdf(DocumentConversionJobStatus status) {
        DocumentVersion version = version(status, false);

        assertFailure(HttpStatus.CONFLICT, () -> create(version.getId(), request(), author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("변환 작업이 없으면 피드백을 저장하지 않는다")
    void rejectsMissingConversionJob() {
        DocumentVersion version = version(null, false);

        assertFailure(HttpStatus.CONFLICT, () -> create(version.getId(), request(), author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("변환 완료 상태여도 PDF가 연결되지 않았으면 409다")
    void rejectsMissingPdf() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, false);

        assertFailure(HttpStatus.CONFLICT, () -> create(version.getId(), request(), author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("페이지 경계를 벗어난 선택 영역은 400이며 저장하지 않는다")
    void rejectsRegionOutsidePage() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        CreateDocumentFeedbackRequest request = new CreateDocumentFeedbackRequest(
                1, .9, .1, .2, .2, "green", "내용");

        assertFailure(HttpStatus.BAD_REQUEST, () -> create(version.getId(), request, author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("PDF 페이지 수를 초과한 페이지는 400이며 저장하지 않는다")
    void rejectsPageOutsidePdf() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        CreateDocumentFeedbackRequest request = new CreateDocumentFeedbackRequest(
                3, .1, .1, .2, .2, "green", "내용");

        assertFailure(HttpStatus.BAD_REQUEST, () -> create(version.getId(), request, author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("지원하지 않는 색상은 400이며 저장하지 않는다")
    void rejectsUnsupportedColor() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        CreateDocumentFeedbackRequest request = new CreateDocumentFeedbackRequest(
                1, .1, .1, .2, .2, "red", "내용");

        assertFailure(HttpStatus.BAD_REQUEST, () -> create(version.getId(), request, author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    @Test
    @DisplayName("공백 피드백은 400이며 저장하지 않는다")
    void rejectsBlankFeedback() {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        CreateDocumentFeedbackRequest request = new CreateDocumentFeedbackRequest(
                1, .1, .1, .2, .2, "green", "\t\n ");

        assertFailure(HttpStatus.BAD_REQUEST, () -> create(version.getId(), request, author));

        assertThat(countFeedback(version.getId())).isZero();
    }

    private DocumentFeedbackResponse create(Long versionId, CreateDocumentFeedbackRequest request, AppUser requester) {
        return service.createFeedback(versionId, request, requester.getIssuer(), requester.getSubject());
    }

    private CreateDocumentFeedbackRequest request() {
        return new CreateDocumentFeedbackRequest(2, .1, .2, .3, .4, "blue", "문장을 수정해 주세요.");
    }

    private void assertFailure(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    private long countFeedback(Long versionId) {
        return entityManager.createQuery("select count(f) from DocumentFeedback f where f.documentVersion.id = :id", Long.class)
                .setParameter("id", versionId).getSingleResult();
    }

    private AppUser user() {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-service@example.com", true, "피드백 작성자");
        entityManager.persist(user);
        return user;
    }

    private DocumentVersion version(DocumentConversionJobStatus status, boolean attachPdf) {
        StoreFile original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        entityManager.persist(original);
        DocumentVersion version = new DocumentVersion(1, "", null, document, original.getObjectKey(),
                original.getFileName(), original.getSizeBytes(), author);
        entityManager.persist(version);
        if (status == null) return version;
        DocumentUpload upload = new DocumentUpload(workspace, null, author, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        entityManager.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) job.complete();
        if (status == DocumentConversionJobStatus.FAILED) job.fail();
        if (attachPdf) {
            StoreFile pdf = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID(),
                    "기획서.pdf", "application/pdf", 2048L);
            entityManager.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
        }
        entityManager.persist(job);
        return version;
    }
}
