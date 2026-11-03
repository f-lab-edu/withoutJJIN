package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
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

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class DocumentUploadIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    // 이 테스트에서는 실제 URL 생성 대신 지정한 결과를 반환한다.
    @MockitoBean
    private S3UploadUrlGenerator uploadUrlGenerator;

    private AppUser member;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        member = appUserRepository.saveAndFlush(new AppUser(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                "upload-test@example.com",
                true,
                "업로드테스트회원"
        ));

        workspace = workspaceRepository.saveAndFlush(
                new Workspace("업로드테스트팀", member)
        );

        workspaceMemberRepository.saveAndFlush(
                new WorkspaceMember(workspace, member)
        );
    }

    @Test
    @DisplayName("워크스페이스 멤버는 업로드 URL을 발급받는다")
    void issuesUploadUrlForWorkspaceMember() throws Exception {
        // 1. 생성기가 반환할 테스트 결과 준비
        String uploadUrl = "https://example.invalid/upload";
        String storageKey =
                "workspaces/" + workspace.getId() + "/uploads/test-file";
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");

        when(uploadUrlGenerator.generate(workspace.getId()))
                .thenReturn(new GeneratedUploadUrl(
                        uploadUrl, storageKey, expiresAt
                ));

        // 2. 로그인한 멤버가 정상적인 파일 정보로 요청
        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", member.getIssuer())
                                .subject(member.getSubject())))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName": "기획서.docx",
                                  "fileSize": 1024,
                                  "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.uploadUrl").value(uploadUrl))
                .andExpect(jsonPath("$.storageKey").value(storageKey))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt.toString()));

        // 3. 권한·파일 검증을 통과해 생성기가 한 번 호출됐는지 확인
        verify(uploadUrlGenerator).generate(workspace.getId());
    }


    @Test
    @DisplayName("워크스페이스 비멤버는 업로드 URL을 발급받을 수 없다")
    void rejectsUploadUrlForNonMember() throws Exception {
        // 회원은 생성하지만, 해당 워크스페이스의 멤버로 등록하지 않는다.
        AppUser outsider = appUserRepository.saveAndFlush(new AppUser(
                "https://accounts.google.com",
                UUID.randomUUID().toString(),
                "outsider@example.com",
                true,
                "비멤버"
        ));

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", outsider.getIssuer())
                                .subject(outsider.getSubject())))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "fileName": "기획서.docx",
                              "fileSize": 1024,
                              "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            }
                            """))
                .andExpect(status().isForbidden());

        // 권한이 없으면 URL 생성 단계까지 진행하면 안 된다.
        verifyNoInteractions(uploadUrlGenerator);
    }
}