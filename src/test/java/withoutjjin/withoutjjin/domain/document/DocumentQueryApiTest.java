package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.*;
import withoutjjin.withoutjjin.domain.document.storage.GeneratedDownloadUrl;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentQueryApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private S3DownloadUrlGenerator generator;

    private AppUser member;
    private Workspace workspace;
    private Document document;
    private String pdfKey;

    @BeforeEach
    void setUp() {
        member = user();
        workspace = new Workspace("조회 테스트팀", member);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("기획서", 1, member, null, workspace);
        em.persist(document);
        pdfKey = "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf";
    }

    @Test
    @DisplayName("소속 멤버는 문서 제목과 최신 변환 상태를 조회하고 저장 키는 응답받지 않는다")
    void returnsDocumentListForMember() throws Exception {
        addVersion(1, DocumentConversionJobStatus.PROCESSING, false);
        flushAndClear();
        mvc.perform(get(listPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(document.getId()))
                .andExpect(jsonPath("$[0].title").value("기획서"))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].conversionStatus").value("PROCESSING"))
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("문서가 없는 워크스페이스는 빈 배열을 반환한다")
    void returnsEmptyArray() throws Exception {
        em.remove(document);
        flushAndClear();
        mvc.perform(get(listPath()).with(login(member)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("초대받아 가입한 멤버도 PDF 조회 URL을 발급받는다")
    void returnsPdfUrlForInvitedMember() throws Exception {
        AppUser invitee = user();
        em.persist(new WorkspaceMember(workspace, invitee));
        DocumentVersion version = addVersion(1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        Instant expires = Instant.parse("2030-01-01T00:05:00Z");
        when(generator.generatePdf(pdfKey)).thenReturn(new GeneratedDownloadUrl("https://example.invalid/pdf", expires));

        mvc.perform(get(pdfPath()).param("objectKey", "other-workspace/private.pdf").with(login(invitee)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documentVersionId").value(version.getId()))
                .andExpect(jsonPath("$.versionNumber").value(version.getVersionNumber()))
                .andExpect(jsonPath("$.pdfUrl").value("https://example.invalid/pdf"))
                .andExpect(jsonPath("$.expiresAt").value(expires.toString()));
        verify(generator).generatePdf(pdfKey);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("비로그인 조회는 리다이렉트 없이 401을 반환한다")
    void rejectsUnauthenticatedRequests(boolean pdf) throws Exception {
        mvc.perform(get(pdf ? pdfPath() : listPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("워크스페이스 비멤버는 목록과 PDF 모두 403이며 서명하지 않는다")
    void rejectsNonMember(boolean pdf) throws Exception {
        AppUser outsider = user();
        addVersion(1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        mvc.perform(get(pdf ? pdfPath() : listPath()).with(login(outsider)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/workspaces/0/documents", "/api/v1/workspaces/-1/documents",
            "/api/v1/workspaces/invalid/documents", "/api/v1/workspaces/9223372036854775808/documents",
            "/api/v1/documents/0/pdf-url", "/api/v1/documents/-1/pdf-url",
            "/api/v1/documents/invalid/pdf-url", "/api/v1/documents/9223372036854775808/pdf-url"})
    @DisplayName("잘못된 워크스페이스·문서 ID는 400을 반환한다")
    void rejectsInvalidIds(String path) throws Exception {
        mvc.perform(get(path).with(login(member))).andExpect(status().isBadRequest());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/workspaces/9223372036854775807/documents",
            "/api/v1/documents/9223372036854775807/pdf-url"})
    @DisplayName("존재하지 않는 워크스페이스·문서는 404를 반환한다")
    void returnsNotFound(String path) throws Exception {
        mvc.perform(get(path).with(login(member))).andExpect(status().isNotFound());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("PDF 변환이 완료되지 않았으면 409이며 서명하지 않는다")
    void rejectsUnfinishedConversion(DocumentConversionJobStatus status) throws Exception {
        addVersion(1, status, false);
        flushAndClear();
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("변환 완료 상태여도 PDF 연결이 없으면 URL을 발급하지 않는다")
    void rejectsMissingPdfFile() throws Exception {
        addVersion(1, DocumentConversionJobStatus.COMPLETED, false);
        flushAndClear();
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("최신 버전이 변환 중이면 이전 버전의 PDF를 대신 발급하지 않는다")
    void doesNotFallBackToPreviousVersionPdf() throws Exception {
        addVersion(1, DocumentConversionJobStatus.COMPLETED, true);
        document.updateRevision(2);
        addVersion(2, DocumentConversionJobStatus.PENDING, false);
        flushAndClear();
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("최신 버전이나 변환 작업이 없으면 URL을 발급하지 않는다")
    void rejectsMissingVersionOrJob(boolean withVersion) throws Exception {
        if (withVersion) addVersion(1, null, false);
        flushAndClear();
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("멤버십이 삭제된 계정은 PDF URL을 새로 발급받지 못한다")
    void rechecksMembershipBeforeIssuingUrl() throws Exception {
        addVersion(1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        when(generator.generatePdf(pdfKey)).thenReturn(new GeneratedDownloadUrl("https://example.invalid/pdf", Instant.now().plusSeconds(300)));
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isOk());
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), member.getId());
        mvc.perform(get(pdfPath()).with(login(member))).andExpect(status().isForbidden());
        verify(generator, times(1)).generatePdf(pdfKey);
    }

    @Test
    @DisplayName("PDF URL과 버전 ID·번호는 같은 최신 DocumentVersion을 가리킨다")
    void returnsVersionMetadataForTheSamePdfObject() throws Exception {
        addVersion(1, DocumentConversionJobStatus.COMPLETED, true);
        document.updateRevision(2);
        pdfKey = "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf";
        DocumentVersion latest = addVersion(2, DocumentConversionJobStatus.COMPLETED, true);
        Long versionId = latest.getId();
        flushAndClear();
        Instant expires = Instant.parse("2030-01-01T00:05:00Z");
        when(generator.generatePdf(pdfKey)).thenReturn(new GeneratedDownloadUrl("https://example.invalid/latest.pdf", expires));

        mvc.perform(get(pdfPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentVersionId").value(versionId))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.pdfUrl").value("https://example.invalid/latest.pdf"));
        verify(generator).generatePdf(pdfKey);
        verifyNoMoreInteractions(generator);
    }

    private String listPath() { return "/api/v1/workspaces/" + workspace.getId() + "/documents"; }
    private String pdfPath() { return "/api/v1/documents/" + document.getId() + "/pdf-url"; }

    private AppUser user() {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "document-api@example.com", true, "문서 조회 회원");
        em.persist(user);
        return user;
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private DocumentVersion addVersion(int number, DocumentConversionJobStatus status, boolean attachPdf) {
        StoreFile original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, document, original.getObjectKey(), original.getFileName(), 1024L, member);
        em.persist(version);
        if (status == null) return version;
        DocumentUpload upload = new DocumentUpload(workspace, null, member, original, 1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) job.complete();
        if (status == DocumentConversionJobStatus.FAILED) job.fail();
        if (attachPdf) {
            StoreFile pdf = new StoreFile(workspace, pdfKey, "기획서.pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
        }
        em.persist(job);
        return version;
    }

    private void flushAndClear() { em.flush(); em.clear(); }
}
