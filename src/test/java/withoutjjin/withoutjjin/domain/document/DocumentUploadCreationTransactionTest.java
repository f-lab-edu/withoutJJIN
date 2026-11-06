package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;
import withoutjjin.withoutjjin.domain.document.service.DocumentService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 테스트 트랜잭션 없이 실제 Service의 커밋과 롤백 경계를 확인한다.
@SpringBootTest
@ActiveProfiles("test")
class DocumentUploadCreationTransactionTest {

    @Autowired private DocumentService documentService;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private StoreFileRepository files;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private S3UploadUrlGenerator uploadUrlGenerator;
    @MockitoSpyBean private DocumentUploadRepository uploads;

    private AppUser requester;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        requester = users.saveAndFlush(new AppUser("https://accounts.google.com",
                UUID.randomUUID().toString(), "upload-transaction@example.com", true, "트랜잭션 테스트 회원"));
        workspace = workspaces.saveAndFlush(new Workspace("업로드 트랜잭션 테스트팀", requester));
        members.saveAndFlush(new WorkspaceMember(workspace, requester));
        when(uploadUrlGenerator.generate(workspace.getId())).thenReturn(new GeneratedUploadUrl(
                "https://example.invalid/upload",
                "workspaces/" + workspace.getId() + "/uploads/" + UUID.randomUUID(),
                Instant.parse("2030-01-01T00:00:00Z")
        ));
    }

    @Test
    @DisplayName("서비스 호출이 끝나면 파일 정보와 업로드 요청이 함께 커밋된다")
    void commitsFileAndUploadRequestTogether() {
        var response = documentService.createUploadUrl(
                workspace.getId(), requester.getIssuer(), requester.getSubject(), request());

        assertThat(response.uploadId()).isPositive();
        assertThat(countFiles()).isEqualTo(1L);
        assertThat(countUploads()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT id FROM document_upload WHERE workspace_id = ?",
                Long.class, workspace.getId())).isEqualTo(response.uploadId());
    }

    @Test
    @DisplayName("URL 생성이 실패하면 파일 정보와 업로드 요청을 저장하지 않는다")
    void leavesNoRecordsWhenUrlGenerationFails() {
        IllegalStateException failure = new IllegalStateException("테스트용 URL 생성 실패");
        when(uploadUrlGenerator.generate(workspace.getId())).thenThrow(failure);

        assertThatThrownBy(() -> documentService.createUploadUrl(
                workspace.getId(), requester.getIssuer(), requester.getSubject(), request()))
                .isSameAs(failure);
        assertThat(countFiles()).isZero();
        assertThat(countUploads()).isZero();
    }

    @Test
    @DisplayName("업로드 요청 저장이 실패하면 앞서 INSERT한 파일 정보도 롤백된다")
    void rollsBackInsertedFileWhenUploadRequestSaveFails() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("테스트용 업로드 요청 저장 실패");
        doAnswer(invocation -> {
            files.flush();
            assertThat(countFiles()).isEqualTo(1L);
            throw failure;
        }).when(uploads).save(any(DocumentUpload.class));

        assertThatThrownBy(() -> documentService.createUploadUrl(
                workspace.getId(), requester.getIssuer(), requester.getSubject(), request()))
                .isSameAs(failure);
        assertThat(countFiles()).isZero();
        assertThat(countUploads()).isZero();
    }

    private CreateUploadUrlRequest request() {
        return new CreateUploadUrlRequest("기획서.docx", 1024L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private long countFiles() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM store_file WHERE workspace_id = ?",
                Long.class, workspace.getId());
    }

    private long countUploads() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM document_upload WHERE workspace_id = ?",
                Long.class, workspace.getId());
    }

    @AfterEach
    void cleanUpOnlyThisTestsData() {
        // 성공 시 실제 커밋되므로, 이번 테스트가 만든 팀의 데이터만 정리한다.
        if (workspace != null) {
            jdbc.update("DELETE FROM document_upload WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM store_file WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (requester != null) users.deleteById(requester.getId());
    }
}
