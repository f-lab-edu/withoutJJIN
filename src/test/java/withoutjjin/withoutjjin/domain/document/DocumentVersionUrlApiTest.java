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
class DocumentVersionUrlApiTest {
    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Instant EXPIRES = Instant.parse("2030-01-01T00:05:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private S3DownloadUrlGenerator generator;

    private AppUser member;
    private Workspace workspace;
    private Document document;

    @BeforeEach
    void setUp() {
        member = user();
        workspace = new Workspace("버전 URL 테스트팀", member);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, member));
        document = document(workspace);
    }

    @Test
    @DisplayName("최신 버전이 변환 중이어도 선택한 이전 버전의 PDF와 ID·번호를 반환한다")
    void issuesPdfForSelectedOlderVersion() throws Exception {
        Asset v1 = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        version(document, 2, DocumentConversionJobStatus.PENDING, false);
        when(generator.generatePdf(v1.pdf().getObjectKey())).thenReturn(generated("v1.pdf"));
        flushAndClear();

        mvc.perform(get(path(v1, false)).param("objectKey", "other-workspace/private.pdf").with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documentVersionId").value(v1.version().getId()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.pdfUrl").value("https://example.invalid/v1.pdf"))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES.toString()))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
        verify(generator).generatePdf(v1.pdf().getObjectKey());
        verifyNoMoreInteractions(generator);
    }

    @Test
    @DisplayName("원본 URL도 최신 버전이 아닌 선택한 버전의 저장 키와 원본 파일명을 사용한다")
    void issuesOriginalForSelectedOlderVersion() throws Exception {
        Asset v1 = version(document, 1, DocumentConversionJobStatus.FAILED, false);
        version(document, 2, DocumentConversionJobStatus.COMPLETED, true);
        when(generator.generateOriginal(v1.original().getObjectKey(), v1.version().getOriginalFileName()))
                .thenReturn(generated("v1.docx"));
        flushAndClear();

        mvc.perform(get(path(v1, true)).param("objectKey", "other-workspace/private.docx").with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documentVersionId").value(v1.version().getId()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.originalFileName").value("서비스 기획서 V1.docx"))
                .andExpect(jsonPath("$.downloadUrl").value("https://example.invalid/v1.docx"))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES.toString()))
                .andExpect(jsonPath("$.storageKey").doesNotExist());
        verify(generator).generateOriginal(v1.original().getObjectKey(), v1.version().getOriginalFileName());
        verifyNoMoreInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("초대 멤버도 선택한 버전의 PDF와 원본을 조회할 수 있다")
    void allowsInvitedMembers(boolean original) throws Exception {
        AppUser invitee = user();
        em.persist(new WorkspaceMember(workspace, invitee));
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        stub(asset, original);
        flushAndClear();
        mvc.perform(get(path(asset, original)).with(login(invitee))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("선택한 버전의 변환이 완료되지 않았으면 이전 PDF를 대신 발급하지 않는다")
    void rejectsUnfinishedPdf(DocumentConversionJobStatus status) throws Exception {
        version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        Asset v2 = version(document, 2, status, false);
        flushAndClear();
        mvc.perform(get(path(v2, false)).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @EnumSource(DocumentConversionJobStatus.class)
    @DisplayName("PDF 변환 상태와 관계없이 완료된 업로드의 원본은 다운로드할 수 있다")
    void allowsOriginalRegardlessOfConversionStatus(DocumentConversionJobStatus status) throws Exception {
        Asset asset = version(document, 1, status, status == DocumentConversionJobStatus.COMPLETED);
        stub(asset, true);
        flushAndClear();
        mvc.perform(get(path(asset, true)).with(login(member))).andExpect(status().isOk());
        verify(generator).generateOriginal(asset.original().getObjectKey(), asset.version().getOriginalFileName());
        verifyNoMoreInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("비로그인은 리다이렉트 없이 401을 반환한다")
    void rejectsAnonymous(boolean original) throws Exception {
        mvc.perform(get(path("1", "1", "1", original)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("워크스페이스 비멤버는 PDF·원본 URL을 발급받지 못한다")
    void rejectsNonMembers(boolean original) throws Exception {
        AppUser outsider = user();
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        mvc.perform(get(path(asset, original)).with(login(outsider))).andExpect(status().isForbidden());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 워크스페이스라도 다른 Document의 DocumentVersion은 조회할 수 없다")
    void rejectsVersionFromAnotherDocument(boolean original) throws Exception {
        Asset other = version(document(workspace), 1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        mvc.perform(get(path(workspace.getId().toString(), document.getId().toString(), other.version().getId().toString(), original)).with(login(member)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("요청한 워크스페이스의 Document가 아니면 URL을 발급하지 않는다")
    void rejectsDocumentFromAnotherWorkspace(boolean original) throws Exception {
        Workspace otherWorkspace = new Workspace("다른 팀", member);
        em.persist(otherWorkspace);
        Asset other = version(document(otherWorkspace), 1, DocumentConversionJobStatus.COMPLETED, true);
        flushAndClear();
        mvc.perform(get(path(workspace.getId().toString(), other.version().getDocument().getId().toString(), other.version().getId().toString(), original)).with(login(member)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("매 요청에서 멤버십을 검사하여 탈퇴한 회원에게 URL을 재발급하지 않는다")
    void rechecksMembership(boolean original) throws Exception {
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        stub(asset, original);
        flushAndClear();
        mvc.perform(get(path(asset, original)).with(login(member))).andExpect(status().isOk());
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), member.getId());
        mvc.perform(get(path(asset, original)).with(login(member))).andExpect(status().isForbidden());
        if (original) verify(generator, times(1)).generateOriginal(asset.original().getObjectKey(), asset.version().getOriginalFileName());
        else verify(generator, times(1)).generatePdf(asset.pdf().getObjectKey());
        verifyNoMoreInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    @DisplayName("잘못된 워크스페이스·문서·버전 ID는 두 URL API 모두 400을 반환한다")
    void rejectsInvalidIds(String invalid) throws Exception {
        for (boolean original : new boolean[]{false, true}) {
            for (String url : new String[]{path(invalid, "1", "1", original), path("1", invalid, "1", original), path("1", "1", invalid, original)}) {
                mvc.perform(get(url).with(login(member))).andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("존재하지 않는 워크스페이스·문서·버전은 404를 반환한다")
    void rejectsMissingResources(boolean original) throws Exception {
        String missing = Long.toString(Long.MAX_VALUE);
        for (String url : new String[]{
                path(missing, document.getId().toString(), "1", original),
                path(workspace.getId().toString(), missing, "1", original),
                path(workspace.getId().toString(), document.getId().toString(), missing, original)}) {
            mvc.perform(get(url).with(login(member))).andExpect(status().isNotFound());
        }
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("DocumentConversionJob 연결이 없으면 URL을 발급하지 않는다")
    void rejectsMissingConversionJob(boolean original) throws Exception {
        Asset asset = version(document, 1, null, false);
        flushAndClear();
        mvc.perform(get(path(asset, original)).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("변환 완료라도 PDF 파일 연결이 없으면 409를 반환한다")
    void rejectsMissingPdf() throws Exception {
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, false);
        flushAndClear();
        mvc.perform(get(path(asset, false)).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("원본 연결이 없거나 DocumentVersion.storageKey와 다르면 URL을 발급하지 않는다")
    void rejectsMissingOrMismatchedOriginal(boolean missing) throws Exception {
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        StoreFile replacement = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID() + ".docx", "다른 원본.docx", DOCX_TYPE, 1024L);
        em.persist(replacement);
        flushAndClear();
        jdbc.update("UPDATE document_upload SET original_file_id = ? WHERE id = ?", missing ? null : replacement.getId(), asset.upload().getId());
        for (boolean original : new boolean[]{false, true}) {
            mvc.perform(get(path(asset, original)).with(login(member))).andExpect(status().isConflict());
        }
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("다른 워크스페이스의 PDF가 잘못 연결되어 있어도 서명하지 않는다")
    void rejectsPdfFromAnotherWorkspace() throws Exception {
        Asset asset = version(document, 1, DocumentConversionJobStatus.COMPLETED, true);
        Workspace otherWorkspace = new Workspace("다른 팀", member);
        em.persist(otherWorkspace);
        StoreFile otherPdf = new StoreFile(otherWorkspace, "workspaces/" + otherWorkspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf", "다른 PDF.pdf", "application/pdf", 2048L);
        em.persist(otherPdf);
        flushAndClear();
        jdbc.update("UPDATE document_upload SET review_pdf_file_id = ? WHERE id = ?", otherPdf.getId(), asset.upload().getId());
        mvc.perform(get(path(asset, false)).with(login(member))).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    private GeneratedDownloadUrl generated(String file) { return new GeneratedDownloadUrl("https://example.invalid/" + file, EXPIRES); }

    private void stub(Asset asset, boolean original) {
        if (original) when(generator.generateOriginal(asset.original().getObjectKey(), asset.version().getOriginalFileName())).thenReturn(generated("original.docx"));
        else when(generator.generatePdf(asset.pdf().getObjectKey())).thenReturn(generated("review.pdf"));
    }

    private String path(Asset asset, boolean original) {
        return path(workspace.getId().toString(), document.getId().toString(), asset.version().getId().toString(), original);
    }

    private String path(String workspaceId, String documentId, String versionId, boolean original) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/versions/" + versionId + (original ? "/original-url" : "/pdf-url");
    }

    private AppUser user() {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(), "version-url@example.com", true, "버전 조회 회원");
        em.persist(user);
        return user;
    }

    private Document document(Workspace target) {
        Document created = new Document("서비스 기획서", 0, member, null, target);
        em.persist(created);
        return created;
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private Asset version(Document target, int number, DocumentConversionJobStatus status, boolean attachPdf) {
        Workspace targetWorkspace = target.getWorkspace();
        StoreFile original = new StoreFile(targetWorkspace, "workspaces/" + targetWorkspace.getId() + "/uploads/" + UUID.randomUUID() + ".docx", "서비스 기획서 V" + number + ".docx", DOCX_TYPE, 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, target, original.getObjectKey(), original.getFileName(), 1024L, member);
        em.persist(version);
        if (number > target.getRevision()) target.updateRevision(number);
        if (status == null) return new Asset(version, original, null, null);
        DocumentUpload upload = new DocumentUpload(targetWorkspace, null, member, original, 1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(target);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) job.complete();
        else if (status == DocumentConversionJobStatus.FAILED) job.fail();
        StoreFile pdf = null;
        if (attachPdf) {
            pdf = new StoreFile(targetWorkspace, "workspaces/" + targetWorkspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf", "기획서.pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
        }
        em.persist(job);
        return new Asset(version, original, pdf, upload);
    }

    private void flushAndClear() { em.flush(); em.clear(); }
    private record Asset(DocumentVersion version, StoreFile original, StoreFile pdf, DocumentUpload upload) {}
}
