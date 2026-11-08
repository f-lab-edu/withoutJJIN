package withoutjjin.withoutjjin.domain.document.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentConversionJobTest {

    private final AppUser requester = new AppUser(
            "https://accounts.google.com", "conversion-job-test", "test@example.com", true, "테스트 회원");
    private final Workspace workspace = new Workspace("변환 테스트팀", requester);
    private final StoreFile original = new StoreFile(workspace, "workspaces/10/uploads/source",
            "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
    private final Document document = new Document("기획서", 1, requester, null, workspace);

    @Test
    @DisplayName("완료된 업로드와 문서 버전을 연결하여 PENDING 변환 작업을 생성한다")
    void createsPendingJobForCompletedUpload() {
        DocumentUpload upload = completedUpload();
        DocumentVersion version = version(document, original.getObjectKey());

        DocumentConversionJob job = new DocumentConversionJob(upload, version);

        assertThat(job.getDocumentUpload()).isSameAs(upload);
        assertThat(job.getDocumentVersion()).isSameAs(version);
        assertThat(job.getStatus()).isEqualTo(DocumentConversionJobStatus.PENDING);
    }

    @Test
    @DisplayName("DocumentUpload가 없으면 변환 작업을 생성하지 않는다")
    void rejectsMissingUpload() {
        DocumentVersion version = version(document, original.getObjectKey());

        assertThatThrownBy(() -> new DocumentConversionJob(null, version))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DocumentUpload");
    }

    @Test
    @DisplayName("DocumentVersion이 없으면 변환 작업을 생성하지 않는다")
    void rejectsMissingVersion() {
        DocumentUpload upload = completedUpload();

        assertThatThrownBy(() -> new DocumentConversionJob(upload, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DocumentVersion");
    }

    @Test
    @DisplayName("완료되지 않은 업로드로 변환 작업을 생성하지 않는다")
    void rejectsPendingUpload() {
        DocumentUpload upload = pendingUpload();
        DocumentVersion version = version(document, original.getObjectKey());

        assertThatThrownBy(() -> new DocumentConversionJob(upload, version))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("완료된");
    }

    @Test
    @DisplayName("업로드와 다른 Document의 버전을 연결하지 않는다")
    void rejectsVersionOfAnotherDocument() {
        DocumentUpload upload = completedUpload();
        Document otherDocument = new Document("다른 문서", 1, requester, null, workspace);
        DocumentVersion version = version(otherDocument, original.getObjectKey());

        assertThatThrownBy(() -> new DocumentConversionJob(upload, version))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Document가 일치");
    }

    @Test
    @DisplayName("업로드 원본과 저장 키가 다른 DocumentVersion을 연결하지 않는다")
    void rejectsVersionWithDifferentOriginalKey() {
        DocumentUpload upload = completedUpload();
        DocumentVersion version = version(document, "workspaces/10/uploads/other");

        assertThatThrownBy(() -> new DocumentConversionJob(upload, version))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("저장 키");
    }

    @Test
    @DisplayName("PENDING 변환 작업을 PROCESSING으로 변경한다")
    void startsPendingJob() {
        DocumentConversionJob job = jobWithStatus(DocumentConversionJobStatus.PENDING);

        job.startProcessing();

        assertThat(job.getStatus()).isEqualTo(DocumentConversionJobStatus.PROCESSING);
    }

    @Test
    @DisplayName("PROCESSING 변환 작업을 COMPLETED로 변경한다")
    void completesProcessingJob() {
        DocumentConversionJob job = jobWithStatus(DocumentConversionJobStatus.PROCESSING);

        job.complete();

        assertThat(job.getStatus()).isEqualTo(DocumentConversionJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("PROCESSING 변환 작업을 FAILED로 변경한다")
    void failsProcessingJob() {
        DocumentConversionJob job = jobWithStatus(DocumentConversionJobStatus.PROCESSING);

        job.fail();

        assertThat(job.getStatus()).isEqualTo(DocumentConversionJobStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PROCESSING", "COMPLETED", "FAILED"})
    @DisplayName("PENDING이 아닌 변환 작업을 다시 시작하지 않는다")
    void rejectsStartingNonPendingJob(DocumentConversionJobStatus status) {
        DocumentConversionJob job = jobWithStatus(status);

        assertThatThrownBy(job::startProcessing)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PENDING");
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "COMPLETED", "FAILED"})
    @DisplayName("PROCESSING이 아닌 변환 작업을 완료하지 않는다")
    void rejectsCompletingNonProcessingJob(DocumentConversionJobStatus status) {
        DocumentConversionJob job = jobWithStatus(status);

        assertThatThrownBy(job::complete)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESSING");
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "COMPLETED", "FAILED"})
    @DisplayName("PROCESSING이 아닌 변환 작업을 실패로 변경하지 않는다")
    void rejectsFailingNonProcessingJob(DocumentConversionJobStatus status) {
        DocumentConversionJob job = jobWithStatus(status);

        assertThatThrownBy(job::fail)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESSING");
        assertThat(job.getStatus()).isEqualTo(status);
    }

    private DocumentConversionJob jobWithStatus(DocumentConversionJobStatus status) {
        DocumentConversionJob job = new DocumentConversionJob(completedUpload(), version(document, original.getObjectKey()));
        if (status != DocumentConversionJobStatus.PENDING) {
            job.startProcessing();
        }
        if (status == DocumentConversionJobStatus.COMPLETED) {
            job.complete();
        } else if (status == DocumentConversionJobStatus.FAILED) {
            job.fail();
        }
        return job;
    }

    private DocumentUpload pendingUpload() {
        return new DocumentUpload(workspace, null, requester, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
    }

    private DocumentUpload completedUpload() {
        DocumentUpload upload = pendingUpload();
        upload.complete(document);
        return upload;
    }

    private DocumentVersion version(Document owner, String objectKey) {
        return new DocumentVersion(1, "", null, owner, objectKey, "기획서.docx", 1024L, requester);
    }
}
