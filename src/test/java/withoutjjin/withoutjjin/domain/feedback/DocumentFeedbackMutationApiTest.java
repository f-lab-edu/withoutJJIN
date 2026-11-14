package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentFeedbackMutationApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private DocumentFeedbackRepository feedbacks;

    private AppUser author;
    private AppUser member;
    private Workspace workspace;
    private DocumentVersion version;
    private Long feedbackId;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        author = user("작성자");
        member = user("다른 멤버");
        workspace = new Workspace("피드백 수정·삭제 API 테스트팀", author);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, author));
        em.persist(new WorkspaceMember(workspace, member));
        Document document = new Document("기획서", 2, author, null, workspace);
        em.persist(document);
        version = new DocumentVersion(1, "", 2, document,
                "test/" + UUID.randomUUID(), "기획서.docx", 1024L, author);
        em.persist(version);
        DocumentFeedback feedback = new DocumentFeedback(version, author, 2,
                .1, .2, .3, .4, FeedbackColor.BLUE, "기존 의견");
        em.persist(feedback);
        feedbackId = feedback.getId();
        em.flush();
        em.clear();
        createdAt = feedbacks.findById(feedbackId).orElseThrow().getCreatedAt();
        em.clear();
    }

    @Test
    @DisplayName("작성자의 PATCH는 200과 수정된 DTO를 반환하고 DB와 목록에 반영된다")
    void updatesContentAndReturnsTheSavedRepresentation() throws Exception {
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body("수정한 의견")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(feedbackId))
                .andExpect(jsonPath("$.documentVersionId").value(version.getId()))
                .andExpect(jsonPath("$.createdBy").value(author.getId()))
                .andExpect(jsonPath("$.content").value("수정한 의견"))
                .andExpect(jsonPath("$.pageNumber").value(2))
                .andExpect(jsonPath("$.color").value("blue"));

        em.flush();
        em.clear();
        DocumentFeedback found = feedbacks.findById(feedbackId).orElseThrow();
        assertThat(found.getContent()).isEqualTo("수정한 의견");
        assertUnchangedMetadata(found);
        mvc.perform(get("/api/v1/document-versions/" + version.getId() + "/feedback").with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("수정한 의견"));
    }

    @Test
    @DisplayName("작성자의 DELETE는 본문 없는 204를 반환하고 목록에서 제거한다")
    void deletesFeedbackAndReturnsNoContent() throws Exception {
        mvc.perform(delete(path(feedbackId)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        em.flush();
        em.clear();
        assertThat(feedbacks.findById(feedbackId)).isEmpty();
        assertThat(em.find(DocumentVersion.class, version.getId())).isNotNull();
        mvc.perform(get("/api/v1/document-versions/" + version.getId() + "/feedback").with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete(path(feedbackId)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("미로그인 요청은 유효한 CSRF가 있어도 401이며 변경하지 않는다")
    void rejectsUnauthenticatedRequests(String method) throws Exception {
        mvc.perform(request(method, String.valueOf(feedbackId)).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("작성자라도 CSRF가 없거나 틀리면 403이며 변경하지 않는다")
    void rejectsMissingOrInvalidCsrf(String method) throws Exception {
        mvc.perform(request(method, String.valueOf(feedbackId)).with(login(author)))
                .andExpect(status().isForbidden());
        mvc.perform(request(method, String.valueOf(feedbackId)).with(login(author))
                        .with(csrf().useInvalidToken().asHeader()))
                .andExpect(status().isForbidden());
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("다른 작성자는 같은 워크스페이스 멤버여도 403이다")
    void rejectsAnotherMember(String method) throws Exception {
        mvc.perform(request(method, String.valueOf(feedbackId)).with(login(member)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("다른 워크스페이스 멤버는 403이다")
    void rejectsAnOutsider(String method) throws Exception {
        AppUser outsider = user("다른 팀 회원");
        Workspace other = new Workspace("다른 팀", outsider);
        em.persist(other);
        em.persist(new WorkspaceMember(other, outsider));
        em.flush();
        em.clear();
        mvc.perform(request(method, String.valueOf(feedbackId)).with(login(outsider)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("작성자가 워크스페이스를 탈퇴하면 403이다")
    void rejectsAnAuthorWhoLeftTheWorkspace(String method) throws Exception {
        em.createQuery("delete from WorkspaceMember m where m.workspace.id = :workspaceId and m.user.id = :userId")
                .setParameter("workspaceId", workspace.getId()).setParameter("userId", author.getId()).executeUpdate();
        em.flush();
        em.clear();
        mvc.perform(request(method, String.valueOf(feedbackId)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    @DisplayName("존재하지 않는 피드백은 404다")
    void rejectsMissingFeedback(String method) throws Exception {
        mvc.perform(request(method, String.valueOf(Long.MAX_VALUE)).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());
        assertOriginalFeedback();
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    @DisplayName("0 이하·문자·Long 범위 초과의 피드백 ID는 400이다")
    void rejectsInvalidIds(String method, String id) throws Exception {
        mvc.perform(request(method, id).with(login(author)).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        assertOriginalFeedback();
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of("PATCH", "DELETE").flatMap(method ->
                Stream.of("0", "-1", "invalid", "9223372036854775808").map(id -> Arguments.of(method, id)));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("내용 누락·공백·1000자 초과·잘못된 JSON은 400이며 기존 내용은 유지된다")
    void rejectsInvalidContent(String json) throws Exception {
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        assertOriginalFeedback();
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\"   \"}",
                "{\"content\":\"\\t\\n\"}", "{\"content\":\"" + "가".repeat(1001) + "\"}", "{", "", "null");
    }

    @Test
    @DisplayName("JSON이 아닌 수정 요청은 415다")
    void rejectsUnsupportedMediaType() throws Exception {
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.TEXT_PLAIN).content("수정한 의견"))
                .andExpect(status().isUnsupportedMediaType());
        assertOriginalFeedback();
    }

    @Test
    @DisplayName("1000자 내용은 허용하고 HTML 문자열은 그대로 응답한다")
    void acceptsTheBoundaryAndPreservesHtmlAsTextData() throws Exception {
        String value = "<script>alert('test')</script>";
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body(value)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value(value));
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(body("가".repeat(1000))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("가".repeat(1000)));
        em.flush();
        em.clear();
        assertThat(feedbacks.findById(feedbackId).orElseThrow().getContent()).hasSize(1000);
    }

    @Test
    @DisplayName("요청 본문으로 작성자·문서 버전·좌표·색상을 바꿀 수 없다")
    void ignoresAttemptsToChangeImmutableFeedbackFields() throws Exception {
        mvc.perform(patch(path(feedbackId)).with(login(author)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"수정한 의견\",\"createdBy\":999,\"documentVersionId\":999,"
                                + "\"pageNumber\":999,\"x\":0.9,\"y\":0.9,\"width\":0.1,\"height\":0.1,\"color\":\"pink\"}"))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
        assertUnchangedMetadata(feedbacks.findById(feedbackId).orElseThrow());
    }

    private MockHttpServletRequestBuilder request(String method, String id) {
        if ("DELETE".equals(method)) return delete("/api/v1/feedback/" + id);
        return patch("/api/v1/feedback/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정한 의견\"}");
    }

    private String body(String content) {
        return jsonMapper.writeValueAsString(Map.of("content", content));
    }

    private String path(Long id) { return "/api/v1/feedback/" + id; }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private void assertOriginalFeedback() {
        em.flush();
        em.clear();
        DocumentFeedback found = feedbacks.findById(feedbackId).orElseThrow();
        assertThat(found.getContent()).isEqualTo("기존 의견");
        assertUnchangedMetadata(found);
    }

    private void assertUnchangedMetadata(DocumentFeedback feedback) {
        assertThat(feedback.getCreatedBy().getId()).isEqualTo(author.getId());
        assertThat(feedback.getDocumentVersion().getId()).isEqualTo(version.getId());
        assertThat(feedback.getPageNumber()).isEqualTo(2);
        assertThat(feedback.getX()).isEqualTo(.1);
        assertThat(feedback.getY()).isEqualTo(.2);
        assertThat(feedback.getWidth()).isEqualTo(.3);
        assertThat(feedback.getHeight()).isEqualTo(.4);
        assertThat(feedback.getColor()).isEqualTo(FeedbackColor.BLUE);
        assertThat(feedback.getCreatedAt()).isEqualTo(createdAt);
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-mutation-api@example.com", true, displayName);
        em.persist(user);
        return user;
    }
}
