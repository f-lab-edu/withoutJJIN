package withoutjjin.withoutjjin.domain.feedback;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.feedback.entity.DocumentFeedback;
import withoutjjin.withoutjjin.domain.feedback.entity.FeedbackColor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentFeedbackQueryApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;

    private AppUser author;
    private AppUser member;
    private Document document;

    @BeforeEach
    void setUp() {
        author = user("피드백 작성자");
        member = user("조회하는 멤버");
        Workspace workspace = new Workspace("피드백 조회 API 테스트팀", author);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, author));
        em.persist(new WorkspaceMember(workspace, member));
        document = new Document("기획서", 2, author, null, workspace);
        em.persist(document);
    }

    @Test
    @DisplayName("같은 워크스페이스 멤버가 CSRF 토큰 없이 이전 버전의 피드백을 조회한다")
    void returnsFeedbackMetadataForMemberWithoutCsrf() throws Exception {
        DocumentVersion version = version(document, 1, 2);
        DocumentFeedback feedback = feedback(version, author);
        em.flush();
        em.clear();

        mvc.perform(get(path(version.getId())).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(feedback.getId()))
                .andExpect(jsonPath("$[0].documentVersionId").value(version.getId()))
                .andExpect(jsonPath("$[0].createdBy").value(author.getId()))
                .andExpect(jsonPath("$[0].createdByDisplayName").value("피드백 작성자"))
                .andExpect(jsonPath("$[0].pageNumber").value(2))
                .andExpect(jsonPath("$[0].x").value(.1))
                .andExpect(jsonPath("$[0].y").value(.2))
                .andExpect(jsonPath("$[0].width").value(.3))
                .andExpect(jsonPath("$[0].height").value(.4))
                .andExpect(jsonPath("$[0].color").value("blue"))
                .andExpect(jsonPath("$[0].content").value("문장을 수정해 주세요."))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].issuer").doesNotExist())
                .andExpect(jsonPath("$[0].subject").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$[0].objectKey").doesNotExist());
    }

    @Test
    @DisplayName("다른 버전과 다른 워크스페이스의 피드백이 응답에 섞이지 않는다")
    void excludesOtherVersionsAndWorkspaces() throws Exception {
        DocumentVersion first = version(document, 1, 2);
        DocumentFeedback expected = feedback(first, author);
        feedback(version(document, 2, 2), author);
        AppUser outsider = user("다른 팀 작성자");
        Workspace otherWorkspace = new Workspace("다른 팀", outsider);
        em.persist(otherWorkspace);
        Document otherDocument = new Document("다른 문서", 1, outsider, null, otherWorkspace);
        em.persist(otherDocument);
        feedback(version(otherDocument, 1, 2), outsider);
        em.flush();
        em.clear();

        mvc.perform(get(path(first.getId())).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(expected.getId()));
    }

    @Test
    @DisplayName("작성 시각이 빠른 순으로 반환하고 같은 시각은 ID로 정렬한다")
    void returnsDeterministicOrder() throws Exception {
        DocumentVersion version = version(document, 1, 2);
        DocumentFeedback later = feedback(version, author);
        DocumentFeedback first = feedback(version, author);
        DocumentFeedback second = feedback(version, member);
        em.flush();
        setCreatedAt(later.getId(), LocalDateTime.of(2030, 1, 2, 12, 0));
        setCreatedAt(first.getId(), LocalDateTime.of(2030, 1, 1, 12, 0));
        setCreatedAt(second.getId(), LocalDateTime.of(2030, 1, 1, 12, 0));
        em.clear();

        mvc.perform(get(path(version.getId())).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[1].id").value(second.getId()))
                .andExpect(jsonPath("$[2].id").value(later.getId()));
    }

    @Test
    @DisplayName("PDF 변환 전 피드백이 없으면 200과 빈 JSON 배열을 반환한다")
    void returnsEmptyListBeforeConversion() throws Exception {
        DocumentVersion version = version(document, 1, null);

        mvc.perform(get(path(version.getId())).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("비로그인 GET 요청은 로그인 리다이렉트 없이 JSON 401이다")
    void rejectsAnonymousRequest() throws Exception {
        DocumentVersion version = version(document, 1, 2);

        mvc.perform(get(path(version.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("비멤버는 피드백 유무와 관계없이 403이다")
    void rejectsNonMember(boolean hasFeedback) throws Exception {
        DocumentVersion version = version(document, 1, 2);
        if (hasFeedback) feedback(version, author);

        mvc.perform(get(path(version.getId())).with(login(user("비멤버"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    @DisplayName("잘못된 DocumentVersion ID는 400이다")
    void rejectsInvalidVersionId(String id) throws Exception {
        mvc.perform(get("/api/v1/document-versions/" + id + "/feedback").with(login(member)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 DocumentVersion은 404다")
    void rejectsMissingVersion() throws Exception {
        mvc.perform(get(path(Long.MAX_VALUE)).with(login(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("작성자 표시 이름이 없으면 이메일 대신 null을 반환한다")
    void returnsNullDisplayNameWithoutEmailFallback() throws Exception {
        DocumentVersion version = version(document, 1, 2);
        feedback(version, user(null));
        em.flush();
        em.clear();

        mvc.perform(get(path(version.getId())).with(login(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].createdByDisplayName").hasJsonPath())
                .andExpect(jsonPath("$[0].createdByDisplayName").value(nullValue()))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    private String path(Long id) {
        return "/api/v1/document-versions/" + id + "/feedback";
    }

    private OidcLoginRequestPostProcessor login(AppUser user) {
        return oidcLogin().idToken(token -> token.claim("iss", user.getIssuer()).subject(user.getSubject()));
    }

    private void setCreatedAt(Long id, LocalDateTime createdAt) {
        em.createNativeQuery("update document_feedback set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", id).executeUpdate();
    }

    private AppUser user(String displayName) {
        AppUser user = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "feedback-query-api@example.com", true, displayName);
        em.persist(user);
        return user;
    }

    private DocumentVersion version(Document targetDocument, int number, Integer pageCount) {
        DocumentVersion version = new DocumentVersion(number, "", pageCount, targetDocument,
                "test/" + UUID.randomUUID(), "기획서.docx", 1024L, targetDocument.getCreatedBy());
        em.persist(version);
        return version;
    }

    private DocumentFeedback feedback(DocumentVersion version, AppUser createdBy) {
        DocumentFeedback feedback = new DocumentFeedback(version, createdBy, 2,
                .1, .2, .3, .4, FeedbackColor.BLUE, "문장을 수정해 주세요.");
        em.persist(feedback);
        return feedback;
    }
}
