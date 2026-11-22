package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionReviewRepository;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentVersionReviewApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentVersionReviewRepository reviews;
    @MockitoBean private S3Client s3Client;
    private DocumentVersionReviewFixture f;

    @BeforeEach
    void setUp() {
        f = new DocumentVersionReviewFixture(em);
        f.setUp();
        em.clear();
    }

    @AfterEach
    void doesNotCallS3() { verifyNoInteractions(s3Client); }

    @Test
    @DisplayName("워크스페이스 멤버는 본인의 피드백 완료를 저장하고 필요한 정보만 반환받는다")
    void completesOwnReviewWithoutRequiringAFeedbackComment() throws Exception {
        mvc.perform(complete(f.first.id(), f.member))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.documentVersionId").value(f.first.id()))
                .andExpect(jsonPath("$.reviewerId").value(f.member.getId()))
                .andExpect(jsonPath("$.reviewerDisplayName").value(f.member.getDisplayName()))
                .andExpect(jsonPath("$.completedAt").isString());
        assertThat(count(f.first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from document_feedback where document_version_id = ?",
                Long.class, f.first.id())).isZero();
    }

    @Test
    @DisplayName("같은 회원의 반복 완료 요청은 기존 ID와 완료 시각을 그대로 반환한다")
    void repeatedPutKeepsTheSameResponse() throws Exception {
        String first = mvc.perform(complete(f.first.id(), f.member)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        em.clear();
        String repeated = mvc.perform(complete(f.first.id(), f.member)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(first);
        assertThat(count(f.first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("본문의 다른 회원 ID와 완료 시각으로 타인의 완료를 기록할 수 없다")
    void ignoresForgedReviewerAndCompletionTime() throws Exception {
        mvc.perform(complete(f.first.id(), f.member).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerId\":" + f.anotherMember.getId() + ",\"completedAt\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewerId").value(f.member.getId()));
        assertThat(reviews.findByDocumentVersionIdAndReviewerId(f.first.id(), f.anotherMember.getId())).isEmpty();
        assertThat(reviews.findByDocumentVersionIdAndReviewerId(f.first.id(), f.member.getId()).orElseThrow().getCompletedAt().getYear())
                .isGreaterThan(2000);
    }

    @Test
    @DisplayName("멤버는 같은 버전에서 완료한 여러 회원을 조회하며 V2 기록은 섞이지 않는다")
    void listsOnlySelectedVersionsReviewersInStableOrder() throws Exception {
        mvc.perform(complete(f.first.id(), f.member)).andExpect(status().isOk());
        mvc.perform(complete(f.first.id(), f.anotherMember)).andExpect(status().isOk());
        mvc.perform(complete(f.second.id(), f.member)).andExpect(status().isOk());
        em.clear();
        mvc.perform(get(path(f.first.id()) + "/reviews").with(login(f.uploader)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].reviewerId").value(f.member.getId()))
                .andExpect(jsonPath("$[1].reviewerId").value(f.anotherMember.getId()))
                .andExpect(jsonPath("$[0].*", hasSize(5)))
                .andExpect(jsonPath("$[0].documentVersionId").value(f.first.id()));
    }

    @Test
    void emptyListDoesNotCreateCompletionRecords() throws Exception {
        mvc.perform(get(path(f.first.id()) + "/reviews").with(login(f.member)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(count(f.first.id())).isZero();
    }

    @Test
    void uploaderCanAlsoRecordTheirOwnCompletion() throws Exception {
        mvc.perform(complete(f.first.id(), f.uploader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId").value(f.uploader.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnauthenticatedRequests(boolean write) throws Exception {
        MockHttpServletRequestBuilder request = write
                ? put(path(f.first.id()) + "/review-completion").with(csrf())
                : get(path(f.first.id()) + "/reviews");
        mvc.perform(request).andExpect(status().isUnauthorized());
        assertThat(count(f.first.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrInvalidCsrf(boolean invalid) throws Exception {
        MockHttpServletRequestBuilder request = put(path(f.first.id()) + "/review-completion").with(login(f.member));
        if (invalid) request.with(csrf().useInvalidToken());
        mvc.perform(request).andExpect(status().isForbidden());
        assertThat(count(f.first.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonMemberCannotCompleteOrReadReviews(boolean write) throws Exception {
        mvc.perform(write ? complete(f.first.id(), f.outsider)
                : get(path(f.first.id()) + "/reviews").with(login(f.outsider)))
                .andExpect(status().isForbidden());
        assertThat(count(f.first.id())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    void rejectsCompletionBeforeSuccessfulConversion(DocumentConversionJobStatus state) throws Exception {
        DocumentVersionReviewFixture.Version selected = f.version(em.find(Document.class, f.document.getId()), 3, state);
        em.flush(); em.clear();
        mvc.perform(complete(selected.id(), f.member)).andExpect(status().isConflict());
        assertThat(count(selected.id())).isZero();
        mvc.perform(get(path(selected.id()) + "/reviews").with(login(f.member)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-job", "missing-pages", "missing-pdf", "wrong-content-type", "wrong-workspace", "pending-upload"})
    void rejectsInconsistentPdfConnections(String condition) throws Exception {
        switch (condition) {
            case "missing-job" -> jdbc.update("delete from document_conversion_job where id = ?", f.first.job().getId());
            case "missing-pages" -> jdbc.update("update document_version set page_count = null where id = ?", f.first.id());
            case "missing-pdf" -> jdbc.update("update document_upload set review_pdf_file_id = null where id = ?", f.first.upload().getId());
            case "wrong-content-type" -> jdbc.update("update store_file set media_type = 'text/plain' where id = ?", f.first.pdf().getId());
            case "wrong-workspace" -> {
                Workspace other = new Workspace("다른 팀", em.find(AppUser.class, f.uploader.getId()));
                em.persist(other); em.flush();
                jdbc.update("update store_file set workspace_id = ? where id = ?", other.getId(), f.first.pdf().getId());
            }
            case "pending-upload" -> jdbc.update("update document_upload set status = 'PENDING' where id = ?", f.first.upload().getId());
            default -> throw new AssertionError(condition);
        }
        em.clear();
        mvc.perform(complete(f.first.id(), f.member)).andExpect(status().isConflict());
        assertThat(count(f.first.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrMismatchedResources(boolean write) throws Exception {
        Document other = new Document("다른 문서", 0, em.find(AppUser.class, f.uploader.getId()), null,
                em.find(Workspace.class, f.workspace.getId()));
        em.persist(other); em.flush();
        String mismatched = base(f.workspace.getId().toString(), other.getId().toString(), f.first.id().toString());
        String missing = path(Long.MAX_VALUE);
        for (String prefix : new String[]{mismatched, missing,
                base(f.workspace.getId().toString(), Long.toString(Long.MAX_VALUE), f.first.id().toString()),
                base(Long.toString(Long.MAX_VALUE), f.document.getId().toString(), f.first.id().toString())}) {
            mvc.perform(action(prefix, write).with(login(f.member))).andExpect(status().isNotFound());
        }
        assertThat(count(f.first.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDocumentFromAnotherWorkspaceEvenForMemberOfBoth(boolean write) throws Exception {
        Workspace other = new Workspace("소속된 다른 팀", em.find(AppUser.class, f.member.getId()));
        em.persist(other);
        em.persist(new WorkspaceMember(other, em.find(AppUser.class, f.member.getId())));
        em.flush();
        String wrong = base(other.getId().toString(), f.document.getId().toString(), f.first.id().toString());
        mvc.perform(action(wrong, write).with(login(f.member))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void rejectsInvalidPathIds(String invalid) throws Exception {
        String team = f.workspace.getId().toString();
        String document = f.document.getId().toString();
        String version = f.first.id().toString();
        for (String prefix : new String[]{base(invalid, document, version), base(team, invalid, version), base(team, document, invalid)}) {
            mvc.perform(action(prefix, true).with(login(f.member))).andExpect(status().isBadRequest());
            mvc.perform(action(prefix, false).with(login(f.member))).andExpect(status().isBadRequest());
        }
        assertThat(count(f.first.id())).isZero();
    }

    @Test
    @DisplayName("완료 기록은 기존 피드백과 PDF·버전 메타데이터를 변경하지 않는다")
    void preservesFeedbackAndOriginalVersionState() throws Exception {
        DocumentFeedback feedback = new DocumentFeedback(em.find(DocumentVersion.class, f.first.id()),
                em.find(AppUser.class, f.member.getId()), 1, .1, .2, .3, .4, FeedbackColor.GREEN, "기존 피드백");
        em.persist(feedback); em.flush();
        Map<String, Object> before = jdbc.queryForMap("select * from document_version where id = ?", f.first.id());
        Map<String, Object> pdfBefore = jdbc.queryForMap("select review_pdf_file_id from document_upload where id = ?", f.first.upload().getId());
        mvc.perform(complete(f.first.id(), f.member)).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select * from document_version where id = ?", f.first.id())).isEqualTo(before);
        assertThat(jdbc.queryForMap("select review_pdf_file_id from document_upload where id = ?", f.first.upload().getId())).isEqualTo(pdfBefore);
        assertThat(jdbc.queryForObject("select content from document_feedback where id = ?", String.class, feedback.getId())).isEqualTo("기존 피드백");
    }

    private static Stream<String> invalidPaths() { return Stream.of("0", "-1", "abc", "9223372036854775808"); }
    private String path(Long versionId) { return base(f.workspace.getId().toString(), f.document.getId().toString(), versionId.toString()); }
    private static String base(String team, String document, String version) {
        return "/api/v1/workspaces/" + team + "/documents/" + document + "/versions/" + version;
    }
    private MockHttpServletRequestBuilder complete(Long versionId, AppUser reviewer) {
        return action(path(versionId), true).with(login(reviewer));
    }
    private static MockHttpServletRequestBuilder action(String prefix, boolean write) {
        return write ? put(prefix + "/review-completion").with(csrf()) : get(prefix + "/reviews");
    }
    private static OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }
    private long count(Long versionId) {
        return jdbc.queryForObject("select count(*) from document_version_review where document_version_id = ?", Long.class, versionId);
    }
}
