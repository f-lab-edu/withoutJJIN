package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentConversionRetryApiTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private S3Client s3Client;

    private AppUser author;
    private AppUser member;
    private Workspace workspace;
    private Document document;
    private DocumentVersion version;
    private StoreFile original;
    private DocumentUpload upload;
    private DocumentConversionJob job;

    @BeforeEach
    void setUp() {
        author = user("업로더");
        member = user("다른 멤버");
        workspace = workspace(author);
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("변환 재시도 문서", 1, author, null, workspace);
        em.persist(document);
        original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "재시도.docx", DOCX, 1024L);
        em.persist(original);
        upload = new DocumentUpload(workspace, document, author, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        em.persist(upload);
        version = new DocumentVersion(1, "기존 버전", null, document, original.getObjectKey(),
                original.getFileName(), 1024L, author);
        em.persist(version);
        job = new DocumentConversionJob(upload, version);
        job.startProcessing();
        job.fail();
        em.persist(job);
        flushAndClear();
    }

    @AfterEach
    void doesNotCallS3FromRetryRequest() {
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("업로더의 재시도는 본문 없는 202이며 기존 버전·업로드·파일·작업을 그대로 사용한다")
    void acceptsFailedConversionWithoutRecreatingResources() throws Exception {
        LocalDateTime createdAt = em.find(DocumentConversionJob.class, job.getId()).getCreatedAt();

        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader())
                        .param("objectKey", "workspaces/999/uploads/not-ours")
                        .param("uploadedBy", member.getId().toString()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        assertResourcesPreserved("PENDING");
        assertThat(em.find(DocumentConversionJob.class, job.getId()).getCreatedAt()).isEqualTo(createdAt);

        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isConflict());
        assertResourcesPreserved("PENDING");
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(post(path(null, null)).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
        assertResourcesPreserved("FAILED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrInvalidCsrf(boolean invalid) throws Exception {
        MockHttpServletRequestBuilder request = post(path(null, null)).with(login(author));
        if (invalid) {
            request.with(csrf().useInvalidToken().asHeader());
        }
        mvc.perform(request).andExpect(status().isForbidden());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsAnotherMemberSpoofingUploader() throws Exception {
        mvc.perform(post(path(null, null)).with(login(member)).with(csrf().asHeader())
                        .param("uploadedBy", author.getId().toString()))
                .andExpect(status().isForbidden());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsDocumentCreatorWhoDidNotUploadVersion() throws Exception {
        jdbc.update("update document_version set uploaded_by = ? where id = ?", member.getId(), version.getId());
        jdbc.update("update document_upload set created_by = ? where id = ?", member.getId(), upload.getId());
        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertJobStatus("FAILED");
    }

    @Test
    void rejectsOutsider() throws Exception {
        AppUser outsider = user("비멤버");
        flushAndClear();
        mvc.perform(post(path(null, null)).with(login(outsider)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsUploaderWhoLeftWorkspace() throws Exception {
        jdbc.update("delete from workspace_member where workspace_id = ? and user_id = ?",
                workspace.getId(), author.getId());
        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertResourcesPreserved("FAILED");
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED"})
    void rejectsNonFailedConversion(DocumentConversionJobStatus state) throws Exception {
        jdbc.update("update document_conversion_job set status = ? where id = ?", state.name(), job.getId());
        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isConflict());
        assertResourcesPreserved(state.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "document", "version"})
    void rejectsMissingPathResource(String field) throws Exception {
        mvc.perform(post(path(field, Long.toString(Long.MAX_VALUE))).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertResourcesPreserved("FAILED");
    }

    @ParameterizedTest
    @MethodSource("invalidPathIds")
    void rejectsInvalidPathIds(String field, String value) throws Exception {
        mvc.perform(post(path(field, value)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsDocumentWorkspaceMismatch() throws Exception {
        Workspace other = workspace(author);
        flushAndClear();
        mvc.perform(post(path("workspace", other.getId().toString())).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsVersionDocumentMismatch() throws Exception {
        Document other = new Document("다른 문서", 0, author, null, workspace);
        em.persist(other);
        flushAndClear();
        mvc.perform(post(path("document", other.getId().toString())).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertResourcesPreserved("FAILED");
    }

    @Test
    void rejectsMissingConversionJobWithoutCreatingOne() throws Exception {
        jdbc.update("delete from document_conversion_job where id = ?", job.getId());
        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from document_conversion_job where document_version_id = ?",
                Long.class, version.getId())).isZero();
        assertThat(em.find(DocumentVersion.class, version.getId())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uploadStatus", "uploadDocument", "uploadWorkspace", "uploadCreator",
            "missingOriginal", "originalWorkspace", "storageKey", "keyPrefix", "mediaType", "pdf", "pageCount"})
    @DisplayName("실패 상태여도 원본 연결이 맞지 않거나 PDF 결과가 이미 있으면 재시도하지 않는다")
    void rejectsInconsistentSource(String field) throws Exception {
        Workspace other = workspace(author);
        Document otherDocument = new Document("다른 문서", 0, author, null, workspace);
        em.persist(otherDocument);
        flushAndClear();
        switch (field) {
            case "uploadStatus" -> jdbc.update("update document_upload set status = 'PENDING' where id = ?", upload.getId());
            case "uploadDocument" -> jdbc.update("update document_upload set document_id = ? where id = ?",
                    otherDocument.getId(), upload.getId());
            case "uploadWorkspace" -> jdbc.update("update document_upload set workspace_id = ? where id = ?",
                    other.getId(), upload.getId());
            case "uploadCreator" -> jdbc.update("update document_upload set created_by = ? where id = ?",
                    member.getId(), upload.getId());
            case "missingOriginal" -> jdbc.update("update document_upload set original_file_id = null where id = ?", upload.getId());
            case "originalWorkspace" -> jdbc.update("update store_file set workspace_id = ? where id = ?",
                    other.getId(), original.getId());
            case "storageKey" -> jdbc.update("update document_version set storage_key = 'different' where id = ?", version.getId());
            case "keyPrefix" -> {
                String key = "workspaces/" + other.getId() + "/uploads/" + UUID.randomUUID();
                jdbc.update("update store_file set object_key = ? where id = ?", key, original.getId());
                jdbc.update("update document_version set storage_key = ? where id = ?", key, version.getId());
            }
            case "mediaType" -> jdbc.update("update store_file set media_type = 'application/pdf' where id = ?", original.getId());
            case "pdf" -> {
                StoreFile pdf = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID(),
                        "이미 변환됨.pdf", "application/pdf", 100L);
                em.persist(pdf);
                em.flush();
                jdbc.update("update document_upload set review_pdf_file_id = ? where id = ?", pdf.getId(), upload.getId());
            }
            case "pageCount" -> jdbc.update("update document_version set page_count = 2 where id = ?", version.getId());
            default -> throw new IllegalArgumentException(field);
        }
        mvc.perform(post(path(null, null)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isConflict());
        assertJobStatus("FAILED");
    }

    private static Stream<Arguments> invalidPathIds() {
        return Stream.of("workspace", "document", "version").flatMap(field ->
                Stream.of("0", "-1", "invalid", "9223372036854775808").map(value -> Arguments.of(field, value)));
    }

    private String path(String field, String value) {
        return "/api/v1/workspaces/" + ("workspace".equals(field) ? value : workspace.getId())
                + "/documents/" + ("document".equals(field) ? value : document.getId())
                + "/versions/" + ("version".equals(field) ? value : version.getId()) + "/conversion-retry";
    }

    private void assertResourcesPreserved(String state) {
        flushAndClear();
        assertJobStatus(state);
        DocumentVersion persistedVersion = em.find(DocumentVersion.class, version.getId());
        assertThat(persistedVersion.getVersionNumber()).isEqualTo(1);
        assertThat(persistedVersion.getStorageKey()).isEqualTo(original.getObjectKey());
        assertThat(persistedVersion.getPageCount()).isNull();
        DocumentUpload persistedUpload = em.find(DocumentUpload.class, upload.getId());
        assertThat(persistedUpload.getOriginalFile().getId()).isEqualTo(original.getId());
        assertThat(persistedUpload.getReviewPdfFile()).isNull();
        assertThat(em.find(StoreFile.class, original.getId())).isNotNull();
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(1);
    }

    private void assertJobStatus(String state) {
        assertThat(jdbc.queryForObject("select status from document_conversion_job where id = ?", String.class, job.getId()))
                .isEqualTo(state);
        assertThat(jdbc.queryForObject("select count(*) from document_conversion_job where document_version_id = ?",
                Long.class, version.getId())).isEqualTo(1);
    }

    private Workspace workspace(AppUser owner) {
        Workspace result = new Workspace("변환 재시도 팀", owner);
        em.persist(result);
        em.persist(new WorkspaceMember(result, owner));
        return result;
    }

    private AppUser user(String name) {
        AppUser result = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "conversion-retry-api@example.invalid", true, name);
        em.persist(result);
        return result;
    }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
