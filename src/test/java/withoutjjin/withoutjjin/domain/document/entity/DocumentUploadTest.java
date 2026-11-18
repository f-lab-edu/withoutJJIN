package withoutjjin.withoutjjin.domain.document.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentUploadTest {

    private static final LocalDateTime EXPIRES_AT =
            LocalDateTime.of(2030, 1, 1, 12, 0);

    private AppUser requester;
    private Workspace workspace;
    private StoreFile originalFile;
    private DocumentUpload upload;

    @BeforeEach
    void setUp() {
        requester = new AppUser(
                "https://accounts.google.com", "upload-requester",
                "upload-test@example.com", true, "업로드 요청자"
        );
        workspace = new Workspace("완료 처리 테스트팀", requester);
        originalFile = new StoreFile(
                workspace, "workspaces/10/uploads/test-file", "기획서.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1024L
        );
        upload = new DocumentUpload(
                workspace, null, requester, originalFile, 1024L, EXPIRES_AT
        );
    }

    @Test
    @DisplayName("Document를 연결하고 PENDING에서 COMPLETED로 변경한다")
    void completesPendingUploadWithDocument() {
        Document document = document(workspace);

        upload.complete(document);

        assertThat(upload.getDocument()).isSameAs(document);
        assertThat(upload.getStatus()).isEqualTo(DocumentUploadStatus.COMPLETED);
        assertThat(upload.getOriginalFile()).isSameAs(originalFile);
        assertThat(upload.getReviewPdfFile()).isNull();
    }

    @Test
    @DisplayName("Workspace 객체가 달라도 같은 Workspace ID이면 완료할 수 있다")
    void acceptsDocumentFromSameWorkspaceId() {
        ReflectionTestUtils.setField(workspace, "id", 10L);
        Workspace reloadedWorkspace = new Workspace("다시 조회한 테스트팀", requester);
        ReflectionTestUtils.setField(reloadedWorkspace, "id", 10L);
        Document document = document(reloadedWorkspace);

        upload.complete(document);

        assertThat(upload.getDocument()).isSameAs(document);
        assertThat(upload.getStatus()).isEqualTo(DocumentUploadStatus.COMPLETED);
    }

    @Test
    @DisplayName("Document가 null이면 document와 status를 변경하지 않는다")
    void rejectsMissingDocumentWithoutChangingUpload() {
        assertThatThrownBy(() -> upload.complete(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document");

        assertPendingWithoutDocument(upload);
    }

    @Test
    @DisplayName("originalFile이 없으면 document와 status를 변경하지 않는다")
    void rejectsMissingOriginalFileWithoutChangingUpload() {
        DocumentUpload uploadWithoutFile = new DocumentUpload(
                workspace, null, requester, null, 1024L, EXPIRES_AT
        );
        Document document = document(workspace);

        assertThatThrownBy(() -> uploadWithoutFile.complete(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("원본 파일");

        assertPendingWithoutDocument(uploadWithoutFile);
    }

    @Test
    @DisplayName("다른 Workspace의 Document이면 document와 status를 변경하지 않는다")
    void rejectsDocumentFromDifferentWorkspaceWithoutChangingUpload() {
        Workspace otherWorkspace = new Workspace("다른 팀", requester);
        Document document = document(otherWorkspace);

        assertThatThrownBy(() -> upload.complete(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 워크스페이스");

        assertPendingWithoutDocument(upload);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentUploadStatus.class,
            names = {"COMPLETED", "FAILED", "EXPIRED"})
    @DisplayName("PENDING이 아닌 status에서는 Document를 연결하지 않고 기존 status를 유지한다")
    void rejectsNonPendingStatusWithoutChangingUpload(DocumentUploadStatus status) {
        // 아직 상태 변경 메서드가 없는 FAILED·EXPIRED 등 기존 상태를 준비한다.
        ReflectionTestUtils.setField(upload, "status", status);
        Document document = document(workspace);

        assertThatThrownBy(() -> upload.complete(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");

        assertThat(upload.getDocument()).isNull();
        assertThat(upload.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("완료된 DocumentUpload를 다시 완료해도 기존 Document가 변경되지 않는다")
    void rejectsRepeatedCompletionWithoutReplacingDocument() {
        Document completedDocument = document(workspace);
        upload.complete(completedDocument);
        Document anotherDocument = document(workspace);

        assertThatThrownBy(() -> upload.complete(anotherDocument))
                .isInstanceOf(IllegalStateException.class);

        assertThat(upload.getDocument()).isSameAs(completedDocument);
        assertThat(upload.getStatus()).isEqualTo(DocumentUploadStatus.COMPLETED);
    }

    @Test
    @DisplayName("PENDING에 이미 연결된 Document를 다른 Document로 교체할 수 없다")
    void rejectsReplacingPreviouslyLinkedDocument() {
        Document existingDocument = document(workspace);
        DocumentUpload linkedUpload = new DocumentUpload(
                workspace, existingDocument, requester, originalFile, 1024L, EXPIRES_AT
        );
        Document anotherDocument = document(workspace);

        assertThatThrownBy(() -> linkedUpload.complete(anotherDocument))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 연결된 Document");

        assertThat(linkedUpload.getDocument()).isSameAs(existingDocument);
        assertThat(linkedUpload.getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING에 연결된 Document와 같은 ID의 Document로 완료할 수 있다")
    void acceptsPreviouslyLinkedDocumentWithSameId() {
        Document existingDocument = document(workspace);
        ReflectionTestUtils.setField(existingDocument, "id", 100L);
        DocumentUpload linkedUpload = new DocumentUpload(
                workspace, existingDocument, requester, originalFile, 1024L, EXPIRES_AT
        );
        Document reloadedDocument = document(workspace);
        ReflectionTestUtils.setField(reloadedDocument, "id", 100L);

        linkedUpload.complete(reloadedDocument);

        assertThat(linkedUpload.getDocument()).isSameAs(reloadedDocument);
        assertThat(linkedUpload.getStatus()).isEqualTo(DocumentUploadStatus.COMPLETED);
    }

    @Test
    @DisplayName("완료된 업로드에 같은 워크스페이스의 PDF를 연결하고 원본을 유지한다")
    void attachesPdfToCompletedUpload() {
        Document document = document(workspace);
        upload.complete(document);
        StoreFile pdf = pdf(workspace, "workspaces/10/review-pdfs/result.pdf");

        upload.attachReviewPdf(pdf);

        assertThat(upload.getReviewPdfFile()).isSameAs(pdf);
        assertThat(upload.getOriginalFile()).isSameAs(originalFile);
        assertThat(upload.getDocument()).isSameAs(document);
        assertThat(upload.getStatus()).isEqualTo(DocumentUploadStatus.COMPLETED);
    }

    @Test
    @DisplayName("PDF가 없으면 reviewPdfFile을 변경하지 않는다")
    void rejectsMissingReviewPdf() {
        upload.complete(document(workspace));

        assertThatThrownBy(() -> upload.attachReviewPdf(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("StoreFile");

        assertThat(upload.getReviewPdfFile()).isNull();
    }

    @Test
    @DisplayName("다른 워크스페이스의 PDF를 연결하지 않는다")
    void rejectsReviewPdfFromAnotherWorkspace() {
        upload.complete(document(workspace));
        StoreFile pdf = pdf(new Workspace("다른 팀", requester), "workspaces/20/review-pdfs/result.pdf");

        assertThatThrownBy(() -> upload.attachReviewPdf(pdf))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("같은 워크스페이스");

        assertThat(upload.getReviewPdfFile()).isNull();
    }

    @Test
    @DisplayName("PDF가 아닌 StoreFile을 reviewPdfFile에 연결하지 않는다")
    void rejectsNonPdfStoreFile() {
        upload.complete(document(workspace));

        assertThatThrownBy(() -> upload.attachReviewPdf(originalFile))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("application/pdf");

        assertThat(upload.getReviewPdfFile()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentUploadStatus.class, names = {"PENDING", "FAILED", "EXPIRED"})
    @DisplayName("완료되지 않은 업로드에는 PDF를 연결하지 않는다")
    void rejectsReviewPdfForNonCompletedUpload(DocumentUploadStatus status) {
        ReflectionTestUtils.setField(upload, "status", status);
        StoreFile pdf = pdf(workspace, "workspaces/10/review-pdfs/result.pdf");

        assertThatThrownBy(() -> upload.attachReviewPdf(pdf))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COMPLETED");

        assertThat(upload.getReviewPdfFile()).isNull();
        assertThat(upload.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("이미 연결된 PDF를 다른 PDF로 교체하지 않는다")
    void rejectsReplacingReviewPdf() {
        upload.complete(document(workspace));
        StoreFile first = pdf(workspace, "workspaces/10/review-pdfs/first.pdf");
        upload.attachReviewPdf(first);
        StoreFile second = pdf(workspace, "workspaces/10/review-pdfs/second.pdf");

        assertThatThrownBy(() -> upload.attachReviewPdf(second))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미");

        assertThat(upload.getReviewPdfFile()).isSameAs(first);
    }

    @Test
    @DisplayName("원본과 저장 키가 같은 PDF를 연결하지 않는다")
    void rejectsReviewPdfUsingOriginalKey() {
        upload.complete(document(workspace));
        StoreFile pdf = pdf(workspace, originalFile.getObjectKey());

        assertThatThrownBy(() -> upload.attachReviewPdf(pdf))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("저장 키");

        assertThat(upload.getReviewPdfFile()).isNull();
    }

    @Test
    @DisplayName("Workspace 객체가 달라도 같은 Workspace ID이면 PDF를 연결한다")
    void acceptsReviewPdfWithSameWorkspaceId() {
        ReflectionTestUtils.setField(workspace, "id", 10L);
        upload.complete(document(workspace));
        Workspace reloaded = new Workspace("다시 조회한 팀", requester);
        ReflectionTestUtils.setField(reloaded, "id", 10L);
        StoreFile pdf = pdf(reloaded, "workspaces/10/review-pdfs/result.pdf");

        upload.attachReviewPdf(pdf);

        assertThat(upload.getReviewPdfFile()).isSameAs(pdf);
    }

    private StoreFile pdf(Workspace owner, String key) {
        return new StoreFile(owner, key, "기획서.pdf", "application/pdf", 2048L);
    }

    private Document document(Workspace documentWorkspace) {
        return new Document("기획서", 1, requester, null, documentWorkspace);
    }

    private void assertPendingWithoutDocument(DocumentUpload target) {
        assertThat(target.getDocument()).isNull();
        assertThat(target.getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
    }
}
