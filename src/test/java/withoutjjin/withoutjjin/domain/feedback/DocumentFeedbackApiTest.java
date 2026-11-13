package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.repository.DocumentFeedbackRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentFeedbackApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private DocumentFeedbackRepository feedbacks;

    private AppUser member;
    private Workspace workspace;
    private Document document;

    @BeforeEach
    void setUp() {
        member = user();
        workspace = new Workspace("피드백 API 테스트팀", member);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("기획서", 2, member, null, workspace);
        em.persist(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"green", "yellow", "blue", "pink"})
    @DisplayName("멤버의 피드백을 지정한 문서 버전에 저장하고 201과 저장 결과를 반환한다")
    void createsFeedbackAndReturnsTheSavedRepresentation(String color) throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        Long versionId = version.getId();
        Long authorId = member.getId();
        String body = validBody().replace("\"green\"", "\"" + color + "\"");

        var result = mvc.perform(request(versionId, body).with(login(member)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.documentVersionId").value(versionId))
                .andExpect(jsonPath("$.createdBy").value(authorId))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.x").value(.1))
                .andExpect(jsonPath("$.y").value(.2))
                .andExpect(jsonPath("$.width").value(.2))
                .andExpect(jsonPath("$.height").value(.3))
                .andExpect(jsonPath("$.color").value(color))
                .andExpect(jsonPath("$.content").value("내용"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andReturn();
        Long id = jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").longValue();
        em.flush();
        em.clear();

        DocumentFeedback saved = feedbacks.findById(id).orElseThrow();
        assertThat(saved.getDocumentVersion().getId()).isEqualTo(versionId);
        assertThat(saved.getDocumentVersion().getVersionNumber()).isEqualTo(1);
        assertThat(saved.getCreatedBy().getId()).isEqualTo(authorId);
        assertThat(saved.getColor().getValue()).isEqualTo(color);
        assertThat(count(versionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 본문에 작성자를 넣어도 로그인한 회원을 작성자로 저장한다")
    void doesNotTrustTheAuthorSuppliedInTheBody() throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        AppUser other = user();
        String body = validBody().replace("}", ",\"createdBy\":" + other.getId() + "}");

        mvc.perform(request(version.getId(), body).with(login(member)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(member.getId()));

        assertThat(count(version.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("비로그인 요청은 JSON 401이며 피드백을 저장하지 않는다")
    void rejectsAnonymousRequests() throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);

        mvc.perform(request(version.getId(), validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));

        assertThat(count(version.getId())).isZero();
    }

    @Test
    @DisplayName("워크스페이스 비멤버는 403이며 피드백을 저장하지 않는다")
    void rejectsNonMembers() throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);

        mvc.perform(request(version.getId(), validBody()).with(login(user())))
                .andExpect(status().isForbidden());

        assertThat(count(version.getId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("CSRF 토큰이 없거나 틀리면 403이며 피드백을 저장하지 않는다")
    void rejectsMissingOrInvalidCsrf(boolean invalidToken) throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);
        var request = post(path(version.getId())).with(login(member))
                .contentType(MediaType.APPLICATION_JSON).content(validBody());
        if (invalidToken) request.with(csrf().useInvalidToken().asHeader());

        mvc.perform(request).andExpect(status().isForbidden());

        assertThat(count(version.getId())).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("잘못된 페이지·좌표·색상·내용 요청은 400이며 저장하지 않는다")
    void rejectsInvalidRequestBodies(String body) throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);

        mvc.perform(request(version.getId(), body).with(login(member)))
                .andExpect(status().isBadRequest());

        assertThat(count(version.getId())).isZero();
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{}", "null", "{\"pageNumber\":", "",
                validBody().replace("\"pageNumber\":1", "\"pageNumber\":0"),
                validBody().replace("\"pageNumber\":1", "\"pageNumber\":3"),
                validBody().replace("\"x\":0.1", "\"x\":-0.1"),
                validBody().replace("\"x\":0.1", "\"x\":0.9"),
                validBody().replace("\"width\":0.2", "\"width\":0"),
                validBody().replace("\"height\":0.3", "\"height\":null"),
                validBody().replace("\"green\"", "\"red\""),
                validBody().replace("\"내용\"", "\"   \""),
                validBody().replace("내용", "가".repeat(1001)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    @DisplayName("잘못된 문서 버전 ID는 400이다")
    void rejectsInvalidVersionIds(String id) throws Exception {
        mvc.perform(post("/api/v1/document-versions/" + id + "/feedback")
                        .with(login(member)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 문서 버전은 404다")
    void rejectsMissingVersion() throws Exception {
        mvc.perform(request(Long.MAX_VALUE, validBody()).with(login(member)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("PDF 변환이 완료되지 않으면 409이며 저장하지 않는다")
    void rejectsUnfinishedConversion(DocumentConversionJobStatus status) throws Exception {
        DocumentVersion version = version(status, false);

        mvc.perform(request(version.getId(), validBody()).with(login(member)))
                .andExpect(status().isConflict());

        assertThat(count(version.getId())).isZero();
    }

    @Test
    @DisplayName("변환 완료 상태여도 PDF 연결이 없으면 409다")
    void rejectsMissingPdf() throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, false);

        mvc.perform(request(version.getId(), validBody()).with(login(member)))
                .andExpect(status().isConflict());

        assertThat(count(version.getId())).isZero();
    }

    @Test
    @DisplayName("JSON이 아닌 등록 요청은 415다")
    void rejectsUnsupportedMediaType() throws Exception {
        DocumentVersion version = version(DocumentConversionJobStatus.COMPLETED, true);

        mvc.perform(post(path(version.getId())).with(login(member)).with(csrf().asHeader())
                        .contentType(MediaType.TEXT_PLAIN).content(validBody()))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(count(version.getId())).isZero();
    }

    private MockHttpServletRequestBuilder request(Long versionId, String body) {
        return post(path(versionId)).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String path(Long id) {
        return "/api/v1/document-versions/" + id + "/feedback";
    }

    private static String validBody() {
        return "{\"pageNumber\":1,\"x\":0.1,\"y\":0.2,\"width\":0.2,\"height\":0.3,\"color\":\"green\",\"content\":\"내용\"}";
    }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private long count(Long versionId) {
        return em.createQuery("select count(f) from DocumentFeedback f where f.documentVersion.id = :id", Long.class)
                .setParameter("id", versionId).getSingleResult();
    }

    private AppUser user() {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-api@example.com", true, "피드백 API 회원");
        em.persist(user);
        return user;
    }

    private DocumentVersion version(DocumentConversionJobStatus status, boolean attachPdf) {
        StoreFile original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "기획서.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(1, "", null, document, original.getObjectKey(),
                original.getFileName(), original.getSizeBytes(), member);
        em.persist(version);
        DocumentUpload upload = new DocumentUpload(workspace, null, member, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) job.complete();
        if (status == DocumentConversionJobStatus.FAILED) job.fail();
        if (attachPdf) {
            StoreFile pdf = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID(),
                    "기획서.pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
        }
        em.persist(job);
        return version;
    }
}
