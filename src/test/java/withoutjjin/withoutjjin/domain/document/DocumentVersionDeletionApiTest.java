package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentVersionDeletionApiTest {

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
        workspace = new Workspace("버전 삭제 API 테스트", author);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, author));
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("이력서", 1, author, null, workspace);
        em.persist(document);
        original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "이력서.docx", DOCX, 1024L);
        em.persist(original);
        upload = new DocumentUpload(workspace, document, author, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        em.persist(upload);
        version = new DocumentVersion(1, "", null, document, original.getObjectKey(),
                original.getFileName(), 1024L, author);
        em.persist(version);
        job = new DocumentConversionJob(upload, version);
        em.persist(job);
        flushAndClear();
    }

    @AfterEach
    void doesNotCallS3FromHttpRequest() {
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("업로더의 DELETE는 본문 없는 204이며 DB 삭제와 원본 삭제 작업 등록을 반영한다")
    void deletesPendingVersionAndIgnoresClientSuppliedObjectKey() throws Exception {
        StoreFile other = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "보존할 원본.docx", DOCX, 512L);
        em.persist(other);
        flushAndClear();

        mvc.perform(delete(path()).with(login(author)).with(csrf().asHeader())
                        .param("objectKey", other.getObjectKey()).param("uploadedBy", member.getId().toString()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        flushAndClear();

        assertThat(em.find(DocumentVersion.class, version.getId())).isNull();
        assertThat(em.find(DocumentConversionJob.class, job.getId())).isNull();
        assertThat(em.find(DocumentUpload.class, upload.getId())).isNull();
        assertThat(em.find(StoreFile.class, original.getId())).isNull();
        assertThat(em.find(StoreFile.class, other.getId())).isNotNull();
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(1);
        assertThat(taskCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select object_key from s3_object_deletion_task where object_key like ?",
                String.class, "workspaces/" + workspace.getId() + "/%")).isEqualTo(original.getObjectKey());

        mvc.perform(delete(path()).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertThat(taskCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("유효한 CSRF가 있어도 미로그인 삭제는 401이며 로그인 화면으로 이동시키지 않는다")
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(delete(path()).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("업로더라도 CSRF가 없거나 잘못되면 403이며 삭제하지 않는다")
    void rejectsMissingOrInvalidCsrf(boolean invalid) throws Exception {
        MockHttpServletRequestBuilder request = delete(path()).with(login(author));
        if (invalid) {
            request.with(csrf().useInvalidToken().asHeader());
        }
        mvc.perform(request).andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    @DisplayName("같은 팀의 다른 멤버는 업로더 ID를 요청에 넣어도 삭제할 수 없다")
    void rejectsAnotherMemberSpoofingUploader() throws Exception {
        mvc.perform(delete(path()).with(login(member)).with(csrf().asHeader())
                        .param("uploadedBy", author.getId().toString()))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    @DisplayName("다른 워크스페이스 멤버도 삭제할 수 없다")
    void rejectsOutsider() throws Exception {
        AppUser outsider = user("비멤버");
        otherWorkspace(outsider);
        flushAndClear();
        mvc.perform(delete(path()).with(login(outsider)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @Test
    @DisplayName("업로더가 워크스페이스에서 탈퇴하면 삭제를 거부한다")
    void rejectsUploaderWhoLeftWorkspace() throws Exception {
        jdbc.update("delete from workspace_member where workspace_id = ? and user_id = ?", workspace.getId(), author.getId());
        mvc.perform(delete(path()).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "document", "version"})
    @DisplayName("존재하지 않는 워크스페이스·문서·버전 경로는 404다")
    void rejectsMissingPathResource(String field) throws Exception {
        mvc.perform(delete(pathReplacing(field, Long.toString(Long.MAX_VALUE)))
                        .with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @ParameterizedTest
    @MethodSource("invalidPathIds")
    @DisplayName("세 경로 ID의 0·음수·문자열·Long 범위 초과 값은 400이다")
    void rejectsInvalidPathIds(String field, String value) throws Exception {
        mvc.perform(delete(pathReplacing(field, value)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        assertUnchanged();
    }

    @Test
    @DisplayName("양쪽 워크스페이스 멤버여도 Document가 속하지 않은 워크스페이스 경로는 404다")
    void rejectsDocumentWorkspaceMismatch() throws Exception {
        Workspace other = otherWorkspace(author);
        flushAndClear();
        mvc.perform(delete(pathReplacing("workspace", other.getId().toString()))
                        .with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @Test
    @DisplayName("다른 Document에 속한 버전은 404다")
    void rejectsVersionDocumentMismatch() throws Exception {
        Document other = new Document("다른 Document", 0, author, null, workspace);
        em.persist(other);
        flushAndClear();
        mvc.perform(delete(pathReplacing("document", other.getId().toString()))
                        .with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertUnchanged();
    }

    @Test
    @DisplayName("변환 중인 버전은 409이며 삭제 작업도 등록하지 않는다")
    void rejectsProcessingVersion() throws Exception {
        em.find(DocumentConversionJob.class, job.getId()).startProcessing();
        flushAndClear();
        mvc.perform(delete(path()).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isConflict());
        assertUnchanged();
    }

    private static Stream<Arguments> invalidPathIds() {
        return Stream.of("workspace", "document", "version").flatMap(field ->
                Stream.of("0", "-1", "invalid", "9223372036854775808").map(value -> Arguments.of(field, value)));
    }

    private String path() {
        return pathReplacing(null, null);
    }

    private String pathReplacing(String field, String value) {
        String workspaceId = "workspace".equals(field) ? value : workspace.getId().toString();
        String documentId = "document".equals(field) ? value : document.getId().toString();
        String versionId = "version".equals(field) ? value : version.getId().toString();
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/versions/" + versionId;
    }

    private void assertUnchanged() {
        flushAndClear();
        assertThat(em.find(DocumentVersion.class, version.getId())).isNotNull();
        assertThat(em.find(DocumentConversionJob.class, job.getId())).isNotNull();
        assertThat(em.find(DocumentUpload.class, upload.getId())).isNotNull();
        assertThat(em.find(StoreFile.class, original.getId())).isNotNull();
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(1);
        assertThat(taskCount()).isZero();
    }

    private long taskCount() {
        return jdbc.queryForObject("select count(*) from s3_object_deletion_task where object_key like ?",
                Long.class, "workspaces/" + workspace.getId() + "/%");
    }

    private Workspace otherWorkspace(AppUser user) {
        Workspace other = new Workspace("다른 팀", user);
        em.persist(other);
        em.persist(new WorkspaceMember(other, user));
        return other;
    }

    private AppUser user(String name) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "version-delete-api@example.invalid", true, name);
        em.persist(user);
        return user;
    }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
