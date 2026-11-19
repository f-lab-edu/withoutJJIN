package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;
import withoutjjin.withoutjjin.domain.document.storage.GeneratedUploadUrl;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadUrlGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentVersionUploadApiTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private EntityManager em;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentVersionRepository versions;
    @Autowired private DocumentUploadRepository uploads;
    @Autowired private StoreFileRepository files;
    @MockitoBean private S3UploadUrlGenerator generator;

    private AppUser member;
    private Workspace workspace;
    private Document document;
    private DocumentVersion firstVersion;
    private long uploadCount, fileCount, documentCount, versionCount;

    @BeforeEach
    void setUp() {
        member = user("버전 업로드 회원");
        workspace = workspaces.saveAndFlush(new Workspace("버전 업로드 팀", member));
        members.saveAndFlush(new WorkspaceMember(workspace, member));
        document = documents.saveAndFlush(new Document("이력서.docx", 1, member, null, workspace));
        firstVersion = versions.saveAndFlush(new DocumentVersion(1, "", 1, document,
                "workspaces/" + workspace.getId() + "/original-v1.docx", "이력서.docx", 1024L, member));
        uploadCount = uploads.count();
        fileCount = files.count();
        documentCount = documents.count();
        versionCount = versions.count();
        when(generator.generate(workspace.getId())).thenAnswer(call -> new GeneratedUploadUrl(
                "https://example.invalid/upload", "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void linksPendingUploadToExistingDocumentWithoutRegisteringVersionTwoYet() throws Exception {
        var result = mvc.perform(authorized(workspace.getId(), document.getId(), member, validRequest()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadId").isNumber())
                .andExpect(jsonPath("$.uploadUrl").value("https://example.invalid/upload"))
                .andReturn();
        CreateUploadUrlResponse response = json.readValue(result.getResponse().getContentAsString(), CreateUploadUrlResponse.class);
        em.flush();
        em.clear();
        DocumentUpload upload = uploads.findById(response.uploadId()).orElseThrow();
        assertThat(upload.getDocument().getId()).isEqualTo(document.getId());
        assertThat(upload.getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(upload.getCreatedBy().getId()).isEqualTo(member.getId());
        assertThat(upload.getStatus()).isEqualTo(DocumentUploadStatus.PENDING);
        assertThat(upload.getOriginalFile().getObjectKey()).isEqualTo(response.storageKey());
        assertThat(upload.getOriginalFile().getFileName()).isEqualTo("이력서.docx");
        assertThat(upload.getOriginalFile().getSizeBytes()).isEqualTo(2048L);
        assertThat(uploads.count()).isEqualTo(uploadCount + 1);
        assertThat(files.count()).isEqualTo(fileCount + 1);
        assertThat(documents.count()).isEqualTo(documentCount);
        assertThat(versions.count()).isEqualTo(versionCount);
        assertThat(documents.findById(document.getId()).orElseThrow().getRevision()).isEqualTo(1);
        assertThat(versions.findById(firstVersion.getId()).orElseThrow().getStorageKey()).endsWith("/original-v1.docx");
    }

    @Test
    void allowsAnotherWorkspaceMemberToUploadANewVersion() throws Exception {
        AppUser colleague = user("동료");
        members.saveAndFlush(new WorkspaceMember(workspace, colleague));
        mvc.perform(authorized(workspace.getId(), document.getId(), colleague, validRequest()))
                .andExpect(status().isOk());
        DocumentUpload upload = uploads.findAll().stream()
                .filter(value -> value.getWorkspace().getId().equals(workspace.getId())).findFirst().orElseThrow();
        assertThat(upload.getCreatedBy().getId()).isEqualTo(colleague.getId());
        assertThat(upload.getDocument().getId()).isEqualTo(document.getId());
    }

    @Test
    void createsIndependentUploadRequestsForRepeatedFileNames() throws Exception {
        var first = mvc.perform(authorized(workspace.getId(), document.getId(), member, validRequest()))
                .andExpect(status().isOk()).andReturn();
        var second = mvc.perform(authorized(workspace.getId(), document.getId(), member, validRequest()))
                .andExpect(status().isOk()).andReturn();
        var a = json.readValue(first.getResponse().getContentAsString(), CreateUploadUrlResponse.class);
        var b = json.readValue(second.getResponse().getContentAsString(), CreateUploadUrlResponse.class);
        assertThat(a.uploadId()).isNotEqualTo(b.uploadId());
        assertThat(a.storageKey()).isNotEqualTo(b.storageKey());
        assertThat(documents.count()).isEqualTo(documentCount);
        assertThat(versions.count()).isEqualTo(versionCount);
    }

    @Test
    void rejectsUnknownDocumentBeforeGeneratingUrlOrSavingStoreFile() throws Exception {
        mvc.perform(authorized(workspace.getId(), Long.MAX_VALUE, member, validRequest()))
                .andExpect(status().isNotFound());
        assertNoUploadSaved();
    }

    @Test
    void rejectsDocumentFromAnotherWorkspaceEvenWhenMemberBelongsToBoth() throws Exception {
        Workspace other = workspaces.saveAndFlush(new Workspace("다른 팀", member));
        members.saveAndFlush(new WorkspaceMember(other, member));
        Document otherDocument = documents.saveAndFlush(new Document("이력서.docx", 1, member, null, other));
        mvc.perform(authorized(workspace.getId(), otherDocument.getId(), member, validRequest()))
                .andExpect(status().isNotFound());
        assertNoUploadSaved();
    }

    @Test
    void rejectsNonMember() throws Exception {
        AppUser outsider = user("비멤버");
        mvc.perform(authorized(workspace.getId(), document.getId(), outsider, validRequest()))
                .andExpect(status().isForbidden());
        assertNoUploadSaved();
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(post(endpoint(workspace.getId(), document.getId())).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
        assertNoUploadSaved();
    }

    @Test
    void requiresCsrf() throws Exception {
        mvc.perform(post(endpoint(workspace.getId(), document.getId()))
                        .with(oidcLogin().idToken(token -> token.claim("iss", member.getIssuer()).subject(member.getSubject())))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
        assertNoUploadSaved();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidDocumentId(long documentId) throws Exception {
        mvc.perform(authorized(workspace.getId(), documentId, member, validRequest()))
                .andExpect(status().isBadRequest());
        assertNoUploadSaved();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void validatesFileMetadataBeforeCreatingUpload(CreateUploadUrlRequest request) throws Exception {
        mvc.perform(authorized(workspace.getId(), document.getId(), member, request))
                .andExpect(status().isBadRequest());
        assertNoUploadSaved();
    }

    static Stream<CreateUploadUrlRequest> invalidRequests() {
        return Stream.of(new CreateUploadUrlRequest(" ", 10L, DOCX),
                new CreateUploadUrlRequest("가".repeat(251) + ".docx", 10L, DOCX),
                new CreateUploadUrlRequest("이력서.pdf", 10L, "application/pdf"),
                new CreateUploadUrlRequest("이력서.docx", 10L, "application/pdf"),
                new CreateUploadUrlRequest("이력서.docx", null, DOCX),
                new CreateUploadUrlRequest("이력서.docx", 0L, DOCX),
                new CreateUploadUrlRequest("이력서.docx", -1L, DOCX),
                new CreateUploadUrlRequest("이력서.docx", 20L * 1024 * 1024 + 1, DOCX),
                new CreateUploadUrlRequest("이력서.docx", 10L, ""));
    }

    @Test
    void leavesNoUploadWhenUrlGenerationFails() throws Exception {
        when(generator.generate(workspace.getId())).thenThrow(new IllegalStateException("테스트 URL 생성 실패"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mvc.perform(authorized(workspace.getId(), document.getId(), member, validRequest())))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(uploads.count()).isEqualTo(uploadCount);
        assertThat(files.count()).isEqualTo(fileCount);
    }

    private void assertNoUploadSaved() {
        verifyNoInteractions(generator);
        assertThat(uploads.count()).isEqualTo(uploadCount);
        assertThat(files.count()).isEqualTo(fileCount);
    }

    private AppUser user(String name) {
        return users.saveAndFlush(new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.com", true, name));
    }

    private CreateUploadUrlRequest validRequest() {
        return new CreateUploadUrlRequest("이력서.docx", 2048L, DOCX);
    }

    private String endpoint(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/uploads";
    }

    private MockHttpServletRequestBuilder authorized(long workspaceId, long documentId,
            AppUser user, CreateUploadUrlRequest request) {
        return post(endpoint(workspaceId, documentId))
                .with(oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject())))
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request));
    }
}
