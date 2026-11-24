package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.conversion.WordPdfConverter;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentVersionQueryApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentConversionJobRepository jobs;
    @MockitoBean private S3DownloadUrlGenerator generator;
    @MockitoBean private S3Client s3Client;
    @MockitoBean private WordPdfConverter converter;

    private AppUser member;
    private Workspace workspace;
    private Document document;

    @BeforeEach
    void setUp() {
        member = user("버전 조회 회원");
        workspace = new Workspace("버전 히스토리 테스트팀", member);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, member));
        document = document(workspace, "서비스 기획서");
    }

    @AfterEach
    void doesNotIssueDownloadUrls() {
        verifyNoInteractions(generator, s3Client, converter);
    }

    @Test
    @DisplayName("버전 생성 순서와 관계없이 번호 내림차순으로 메타데이터를 반환한다")
    void returnsVersionHistoryInDescendingVersionOrder() throws Exception {
        AppUser uploader = user("새 버전 업로더");
        em.persist(new WorkspaceMember(workspace, uploader));
        DocumentVersion v3 = version(document, 3, uploader, DocumentConversionJobStatus.COMPLETED);
        DocumentVersion v1 = version(document, 1, member, DocumentConversionJobStatus.FAILED);
        DocumentVersion v2 = version(document, 2, member, DocumentConversionJobStatus.PROCESSING);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(v3.getId()))
                .andExpect(jsonPath("$[0].documentId").value(document.getId()))
                .andExpect(jsonPath("$[0].versionNumber").value(3))
                .andExpect(jsonPath("$[0].originalFileName").value("서비스 기획서 V3.docx"))
                .andExpect(jsonPath("$[0].fileSize").value(1024))
                .andExpect(jsonPath("$[0].pageCount").value(2))
                .andExpect(jsonPath("$[0].uploadedBy").value(uploader.getId()))
                .andExpect(jsonPath("$[0].uploadedByDisplayName").value("새 버전 업로더"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].conversionStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[1].id").value(v2.getId()))
                .andExpect(jsonPath("$[1].versionNumber").value(2))
                .andExpect(jsonPath("$[1].conversionStatus").value("PROCESSING"))
                .andExpect(jsonPath("$[2].id").value(v1.getId()))
                .andExpect(jsonPath("$[2].versionNumber").value(1))
                .andExpect(jsonPath("$[2].conversionStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$[0].pdfUrl").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    @DisplayName("같은 워크스페이스의 다른 문서와 다른 워크스페이스의 버전은 포함하지 않는다")
    void includesOnlyTheSelectedDocument() throws Exception {
        DocumentVersion selected = version(document, 1, member, DocumentConversionJobStatus.PENDING);
        version(document(workspace, "같은 팀의 다른 문서"), 1, member, DocumentConversionJobStatus.COMPLETED);
        Workspace otherWorkspace = new Workspace("다른 팀", member);
        em.persist(otherWorkspace);
        version(document(otherWorkspace, "다른 팀 문서"), 1, member, DocumentConversionJobStatus.COMPLETED);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(selected.getId()));
    }

    @Test
    @DisplayName("문서를 올리지 않은 초대 멤버도 버전 히스토리를 조회할 수 있다")
    void allowsInvitedMembers() throws Exception {
        AppUser invitee = user("초대 멤버");
        em.persist(new WorkspaceMember(workspace, invitee));
        failedVersion(document, 1, member);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uploadedBy").value(member.getId()))
                .andExpect(jsonPath("$[0].failureStage").value("ORIGINAL_DOWNLOAD"))
                .andExpect(jsonPath("$[0].failureCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("DocumentVersion이 없는 Document는 빈 배열을 반환한다")
    void returnsEmptyHistory() throws Exception {
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @EnumSource(DocumentConversionJobStatus.class)
    @DisplayName("각 DocumentVersion의 변환 대기·실행 중·완료·실패 상태를 반환한다")
    void returnsEachConversionStatus(DocumentConversionJobStatus conversionStatus) throws Exception {
        version(document, 1, member, conversionStatus);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversionStatus").value(conversionStatus.name()));
    }

    @Test
    @DisplayName("변환 작업이 없는 버전도 포함하고 다른 버전의 상태를 대신 사용하지 않는다")
    void preservesVersionWithoutConversionJob() throws Exception {
        failedVersion(document, 1, member);
        version(document, 2, member, null);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].conversionStatus").doesNotExist())
                .andExpect(jsonPath("$[0].pageCount").doesNotExist())
                .andExpect(nullFailureFields("$[0]"))
                .andExpect(jsonPath("$[1].conversionStatus").value("FAILED"))
                .andExpect(jsonPath("$[1].failureCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("업로더 표시 이름이 없어도 버전을 누락하지 않는다")
    void preservesVersionWithoutUploaderDisplayName() throws Exception {
        AppUser uploader = user(null);
        version(document, 1, uploader, DocumentConversionJobStatus.PENDING);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uploadedBy").value(uploader.getId()))
                .andExpect(jsonPath("$[0].uploadedByDisplayName").doesNotExist());
    }

    @Test
    @DisplayName("비로그인 요청은 리다이렉트 없이 401을 반환한다")
    void rejectsAnonymousRequests() throws Exception {
        failedVersion(document, 1, member);
        flushAndClear();
        mvc.perform(get(historyPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("워크스페이스 비멤버는 버전 목록을 조회할 수 없다")
    void rejectsNonMembers() throws Exception {
        AppUser outsider = user("비멤버");
        failedVersion(document, 1, member);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("요청한 워크스페이스의 문서가 아니면 404를 반환한다")
    void rejectsDocumentFromAnotherWorkspace() throws Exception {
        Workspace otherWorkspace = new Workspace("다른 팀", member);
        em.persist(otherWorkspace);
        Document otherDocument = document(otherWorkspace, "다른 팀 문서");
        failedVersion(otherDocument, 1, member);
        flushAndClear();
        mvc.perform(get(path(workspace.getId().toString(), otherDocument.getId().toString())).with(login(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("멤버십 삭제 후에는 이전에 조회한 버전 목록도 다시 조회할 수 없다")
    void rechecksMembershipOnEveryRequest() throws Exception {
        failedVersion(document, 1, member);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member))).andExpect(status().isOk());
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspace.getId(), member.getId());
        mvc.perform(get(historyPath()).with(login(member))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    @DisplayName("잘못된 워크스페이스 ID는 400을 반환한다")
    void rejectsInvalidWorkspaceIds(String id) throws Exception {
        mvc.perform(get(path(id, document.getId().toString())).with(login(member)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    @DisplayName("잘못된 문서 ID는 400을 반환한다")
    void rejectsInvalidDocumentIds(String id) throws Exception {
        mvc.perform(get(path(workspace.getId().toString(), id)).with(login(member)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 워크스페이스는 404를 반환한다")
    void rejectsMissingWorkspace() throws Exception {
        mvc.perform(get(path(Long.toString(Long.MAX_VALUE), document.getId().toString())).with(login(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 문서는 404를 반환한다")
    void rejectsMissingDocument() throws Exception {
        mvc.perform(get(path(workspace.getId().toString(), Long.toString(Long.MAX_VALUE))).with(login(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FAILED 응답에 저장된 코드와 ISO 시각을 포함하고 승인된 13개 필드만 반환한다")
    void returnsFailureFieldsWithoutExposingInternalData() throws Exception {
        DocumentVersion selected = failedVersion(document, 1, member);
        Long jobId = jobs.findByDocumentVersionId(selected.getId()).orElseThrow().getId();
        LocalDateTime recordedAt = LocalDateTime.of(2026, 11, 21, 12, 34, 56, 123456000);
        em.flush();
        jdbc.update("update document_conversion_job set failed_at = ? where id = ?", recordedAt, jobId);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(selected.getId()))
                .andExpect(jsonPath("$[0].conversionStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].failureStage").value("ORIGINAL_DOWNLOAD"))
                .andExpect(jsonPath("$[0].failureCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$[0].failedAt").value(recordedAt.toString()))
                .andExpect(jsonPath("$[0]").value(aMapWithSize(13)))
                .andExpect(jsonPath("$[0].failureMessage").doesNotExist())
                .andExpect(jsonPath("$[0].exception").doesNotExist())
                .andExpect(jsonPath("$[0].cause").doesNotExist())
                .andExpect(jsonPath("$[0].stackTrace").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    @DisplayName("실패 정보가 없는 기존 FAILED도 세 필드를 명시적 null로 응답한다")
    void returnsNullFailureFieldsForLegacyJob() throws Exception {
        DocumentVersion selected = failedVersion(document, 1, member);
        Long jobId = jobs.findByDocumentVersionId(selected.getId()).orElseThrow().getId();
        em.flush();
        jdbc.update("update document_conversion_job set failure_stage = null, failure_code = null, failed_at = null where id = ?", jobId);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversionStatus").value("FAILED"))
                .andExpect(nullFailureFields("$[0]"));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED"})
    @DisplayName("FAILED 이외 상태에서는 남아 있는 이전 실패 정보를 JSON에 노출하지 않는다")
    void returnsNullFailureFieldsForOtherStatuses(DocumentConversionJobStatus status) throws Exception {
        DocumentVersion selected = failedVersion(document, 1, member);
        Long jobId = jobs.findByDocumentVersionId(selected.getId()).orElseThrow().getId();
        em.flush();
        jdbc.update("update document_conversion_job set status = ? where id = ?", status.name(), jobId);
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversionStatus").value(status.name()))
                .andExpect(nullFailureFields("$[0]"));
    }

    @Test
    @DisplayName("조회·재시도·재조회 시 같은 버전은 PENDING과 null 실패 정보를 반환하고 다른 버전은 유지한다")
    void returnsClearedFailureAfterRetryApi() throws Exception {
        DocumentVersion sibling = failedVersion(document, 1, member);
        DocumentVersion selected = failedVersion(document, 2, member);
        flushAndClear();
        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(selected.getId()))
                .andExpect(jsonPath("$[0].failureCode").value("ACCESS_DENIED"));

        mvc.perform(post(historyPath() + "/" + selected.getId() + "/conversion-retry")
                        .with(login(member)).with(csrf().asHeader()))
                .andExpect(status().isAccepted());
        flushAndClear();

        mvc.perform(get(historyPath()).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(selected.getId()))
                .andExpect(jsonPath("$[0].versionNumber").value(2))
                .andExpect(jsonPath("$[0].conversionStatus").value("PENDING"))
                .andExpect(nullFailureFields("$[0]"))
                .andExpect(jsonPath("$[1].id").value(sibling.getId()))
                .andExpect(jsonPath("$[1].conversionStatus").value("FAILED"))
                .andExpect(jsonPath("$[1].failureCode").value("ACCESS_DENIED"));
        Long jobId = jobs.findByDocumentVersionId(selected.getId()).orElseThrow().getId();
        assertThat(jdbc.queryForMap("select failure_stage, failure_code, failed_at from document_conversion_job where id = ?",
                jobId).values()).containsOnlyNulls();
    }

    private ResultMatcher nullFailureFields(String prefix) {
        return result -> {
            for (String field : new String[]{"failureStage", "failureCode", "failedAt"}) {
                jsonPath(prefix + "." + field).hasJsonPath().match(result);
                jsonPath(prefix + "." + field).value(nullValue()).match(result);
            }
        };
    }

    private DocumentVersion failedVersion(Document target, int number, AppUser uploader) {
        DocumentVersion selected = version(target, number, uploader, DocumentConversionJobStatus.PROCESSING);
        jobs.findByDocumentVersionId(selected.getId()).orElseThrow()
                .fail(DocumentConversionFailureStage.ORIGINAL_DOWNLOAD, DocumentConversionFailureCode.ACCESS_DENIED);
        return selected;
    }

    private String historyPath() { return path(workspace.getId().toString(), document.getId().toString()); }

    private String path(String workspaceId, String documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/versions";
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "version-history@example.com", true, displayName);
        em.persist(user);
        return user;
    }

    private Document document(Workspace target, String title) {
        Document created = new Document(title, 0, member, null, target);
        em.persist(created);
        return created;
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private DocumentVersion version(Document target, int number, AppUser uploader, DocumentConversionJobStatus status) {
        Workspace targetWorkspace = target.getWorkspace();
        StoreFile original = new StoreFile(targetWorkspace,
                "workspaces/" + targetWorkspace.getId() + "/uploads/" + UUID.randomUUID() + ".docx",
                "서비스 기획서 V" + number + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, target,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), uploader);
        em.persist(version);
        if (number > target.getRevision()) target.updateRevision(number);
        if (status == null) return version;

        DocumentUpload upload = new DocumentUpload(targetWorkspace, null, uploader, original, 1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(target);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) {
            StoreFile pdf = new StoreFile(targetWorkspace,
                    "workspaces/" + targetWorkspace.getId() + "/review-pdfs/" + UUID.randomUUID() + ".pdf",
                    "서비스 기획서 V" + number + ".pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
            job.complete();
        } else if (status == DocumentConversionJobStatus.FAILED) job.fail();
        em.persist(job);
        return version;
    }

    private void flushAndClear() { em.flush(); em.clear(); }
}
