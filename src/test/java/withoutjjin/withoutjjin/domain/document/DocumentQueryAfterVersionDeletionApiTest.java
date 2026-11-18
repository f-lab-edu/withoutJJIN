package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.storage.GeneratedDownloadUrl;
import withoutjjin.withoutjjin.domain.document.storage.S3DownloadUrlGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentQueryAfterVersionDeletionApiTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Instant EXPIRES = Instant.parse("2030-01-01T00:05:00Z");

    @Autowired private EntityManager em;
    @Autowired private MockMvc mvc;
    @MockitoBean private S3DownloadUrlGenerator generator;

    private AppUser uploader;
    private Workspace workspace;
    private Document document;
    private Asset v1;
    private Asset v2;
    private Asset v3;

    @BeforeEach
    void setUp() {
        uploader = new AppUser("https://accounts.google.com", UUID.randomUUID().toString(),
                "remaining-version@example.invalid", true, "업로더");
        em.persist(uploader);
        workspace = new Workspace("버전 삭제 후 조회 테스트", uploader);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, uploader));
        document = new Document("이력서", 3, uploader, null, workspace);
        em.persist(document);
        // 행 ID와 버전 번호의 정렬 순서가 다르도록 저장한다.
        v2 = version(2, DocumentConversionJobStatus.COMPLETED);
        v1 = version(1, DocumentConversionJobStatus.COMPLETED);
        v3 = version(3, DocumentConversionJobStatus.COMPLETED);
        flushAndClear();
    }

    @Test
    @DisplayName("V3 삭제 후 목록과 최신 PDF는 남은 V2를 가리키고 Document.revision은 3을 유지한다")
    void latestDeletionReturnsRemainingV2ForListPdfAndHistory() throws Exception {
        deleteVersion(v3);
        stubPdf(v2);

        expectList(2, 2, "COMPLETED");
        expectPdf(v2);
        mvc.perform(get(historyPath()).with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(v2.version().getId()))
                .andExpect(jsonPath("$[1].id").value(v1.version().getId()));
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(3);
        verify(generator).generatePdf(v2.pdf().getObjectKey());
        verifyNoMoreInteractions(generator);
    }

    @Test
    @DisplayName("중간 버전을 삭제하면 최신 버전은 V3이고 개수만 줄어든다")
    void deletingMiddleVersionDoesNotChangeLatestPdf() throws Exception {
        deleteVersion(v2);
        stubPdf(v3);

        expectList(3, 2, "COMPLETED");
        expectPdf(v3);
        verify(generator).generatePdf(v3.pdf().getObjectKey());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionJobStatus.class, names = {"PENDING", "PROCESSING", "FAILED"})
    @DisplayName("남은 최신 버전이 변환 대기·진행·실패라면 이전 완료 PDF로 대체하지 않는다")
    void doesNotSkipUnreadyHighestRemainingVersion(DocumentConversionJobStatus status) throws Exception {
        Document managed = em.find(Document.class, document.getId());
        managed.updateRevision(5);
        version(4, status);
        Asset v5 = version(5, DocumentConversionJobStatus.COMPLETED);
        flushAndClear();
        deleteVersion(v5);

        expectList(4, 4, status.name());
        mvc.perform(get(pdfPath()).with(login())).andExpect(status().isConflict());
        verifyNoInteractions(generator);
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(5);
    }

    @Test
    @DisplayName("남은 최신 버전에 변환 작업이 없으면 상태는 null이고 최신 PDF 조회는 409다")
    void doesNotSkipHighestVersionWithoutConversionJob() throws Exception {
        em.find(Document.class, document.getId()).updateRevision(5);
        version(4, null);
        Asset v5 = version(5, DocumentConversionJobStatus.COMPLETED);
        flushAndClear();
        deleteVersion(v5);

        mvc.perform(get(listPath()).with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revision").value(4))
                .andExpect(jsonPath("$[0].versionCount").value(4))
                .andExpect(jsonPath("$[0].conversionStatus").value(nullValue()));
        mvc.perform(get(pdfPath()).with(login())).andExpect(status().isConflict());
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("모든 버전 삭제 후 Document는 목록에 남고 버전 번호·개수는 0이며 PDF는 발급하지 않는다")
    void allVersionsDeletedKeepsEmptyDocumentAndIssuedRevision() throws Exception {
        deleteVersion(v3);
        deleteVersion(v2);
        deleteVersion(v1);

        mvc.perform(get(listPath()).with(login()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(document.getId()))
                .andExpect(jsonPath("$[0].revision").value(0))
                .andExpect(jsonPath("$[0].versionCount").value(0))
                .andExpect(jsonPath("$[0].conversionStatus").value(nullValue()));
        mvc.perform(get(historyPath()).with(login()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(pdfPath()).with(login())).andExpect(status().isConflict());
        assertThat(em.find(Document.class, document.getId()).getRevision()).isEqualTo(3);
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("삭제한 버전의 PDF·원본 URL은 404이며 서명하지 않는다")
    void deletedVersionUrlsReturnNotFound() throws Exception {
        deleteVersion(v3);

        String path = historyPath() + "/" + v3.version().getId();
        mvc.perform(get(path + "/pdf-url").with(login())).andExpect(status().isNotFound());
        mvc.perform(get(path + "/original-url").with(login())).andExpect(status().isNotFound());
        verifyNoInteractions(generator);
    }

    private void expectList(int number, int count, String conversionStatus) throws Exception {
        mvc.perform(get(listPath()).with(login()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revision").value(number))
                .andExpect(jsonPath("$[0].versionCount").value(count))
                .andExpect(jsonPath("$[0].conversionStatus").value(conversionStatus));
    }

    private void expectPdf(Asset asset) throws Exception {
        mvc.perform(get(pdfPath()).with(login()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documentVersionId").value(asset.version().getId()))
                .andExpect(jsonPath("$.versionNumber").value(asset.version().getVersionNumber()))
                .andExpect(jsonPath("$.pdfUrl").value("https://example.invalid/v" + asset.version().getVersionNumber() + ".pdf"));
    }

    private void stubPdf(Asset asset) {
        when(generator.generatePdf(asset.pdf().getObjectKey())).thenReturn(new GeneratedDownloadUrl(
                "https://example.invalid/v" + asset.version().getVersionNumber() + ".pdf", EXPIRES));
    }

    private void deleteVersion(Asset asset) throws Exception {
        mvc.perform(delete(historyPath() + "/" + asset.version().getId())
                        .with(login()).with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        flushAndClear();
    }

    private Asset version(int number, DocumentConversionJobStatus status) {
        StoreFile original = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                "이력서-V" + number + ".docx", DOCX, 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, document,
                original.getObjectKey(), original.getFileName(), 1024L, uploader);
        em.persist(version);
        if (status == null) {
            return new Asset(version, null);
        }
        DocumentUpload upload = new DocumentUpload(workspace, document, uploader, original,
                1024L, LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(document);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        em.persist(job);
        if (status != DocumentConversionJobStatus.PENDING) {
            job.startProcessing();
        }
        if (status == DocumentConversionJobStatus.FAILED) {
            job.fail();
        }
        StoreFile pdf = null;
        if (status == DocumentConversionJobStatus.COMPLETED) {
            pdf = new StoreFile(workspace, "workspaces/" + workspace.getId() + "/review-pdfs/" + UUID.randomUUID(),
                    "이력서-V" + number + ".pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
            job.complete();
        }
        return new Asset(version, pdf);
    }

    private String listPath() {
        return "/api/v1/workspaces/" + workspace.getId() + "/documents";
    }

    private String historyPath() {
        return listPath() + "/" + document.getId() + "/versions";
    }

    private String pdfPath() {
        return "/api/v1/documents/" + document.getId() + "/pdf-url";
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor login() {
        return oidcLogin().idToken(token -> token.claim("iss", uploader.getIssuer()).subject(uploader.getSubject()));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private record Asset(DocumentVersion version, StoreFile pdf) {
    }
}
