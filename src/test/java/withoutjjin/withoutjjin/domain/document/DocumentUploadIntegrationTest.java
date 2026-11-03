package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
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
import java.util.stream.Stream;

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
    private JsonMapper jsonMapper;


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
                                .claim("iss", outsider.getIssuer())  // 로그인 유저 정보 등록
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
                .andExpect(status().isForbidden()); // 응답 403

        // 권한이 없으면 URL 생성 단계까지 진행하면 안 된다.
        verifyNoInteractions(uploadUrlGenerator);
    }




    @Test
    @DisplayName("존재하지 않는 워크스페이스에는 업로드 URL을 발급하지 않는다")
    void rejectsUploadUrlForMissingWorkspace() throws Exception {
        // 확실히 존재하지 않는 ID를 준비한다.
        Workspace deletedWorkspace = workspaceRepository.saveAndFlush(
                new Workspace("삭제할테스트팀", member)
        );
        Long missingWorkspaceId = deletedWorkspace.getId();

        workspaceRepository.delete(deletedWorkspace);
        workspaceRepository.flush();

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        missingWorkspaceId
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
                .andExpect(status().isNotFound()); // 404

        verifyNoInteractions(uploadUrlGenerator);
    }


    @ParameterizedTest
    @MethodSource("invalidFileInfos")
    @DisplayName("허용 기준을 벗어난 파일 정보는 거부한다")
    void rejectsInvalidFileInfo(
            String fileName,
            long fileSize,
            String contentType
    ) throws Exception {
        String requestBody = """
            {
              "fileName": "%s",
              "fileSize": %d,
              "contentType": "%s"
            }
            """.formatted(fileName, fileSize, contentType);

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", member.getIssuer())
                                .subject(member.getSubject())))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(uploadUrlGenerator);
    }

    static Stream<Arguments> invalidFileInfos() {
        String docxContentType =
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

        return Stream.of(
                // 최대 용량보다 1바이트 큼
                Arguments.of(
                        "기획서.docx",
                        20L * 1024 * 1024 + 1,
                        docxContentType
                ),
                // 허용하지 않는 확장자
                Arguments.of(
                        "기획서.pdf",
                        1024L,
                        docxContentType
                ),
                // 이름 중간에 docx가 있어도 실제 확장자는 exe
                Arguments.of(
                        "기획서.docx.exe",
                        1024L,
                        docxContentType
                ),
                // 확장자는 맞지만 Content-Type이 다름
                Arguments.of(
                        "기획서.docx",
                        1024L,
                        "application/pdf"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidBasicFileInfos")
    @DisplayName("파일 정보의 필수값과 기본 조건을 검증한다")
    void rejectsInvalidBasicFileInfo(
            String fileName,
            Long fileSize,
            String contentType
    ) throws Exception {
        CreateUploadUrlRequest request = new CreateUploadUrlRequest(
                fileName, fileSize, contentType
        );

        // 요청 DTO를 JSON 문자열로 변환한다.
        String requestBody = jsonMapper.writeValueAsString(request);

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", member.getIssuer())
                                .subject(member.getSubject())))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)) // jsonMapper를 이용해서 DTO를 제이슨형태로 바꿔서 전달
                .andExpect(status().isBadRequest());

        verifyNoInteractions(uploadUrlGenerator);
    }

    static Stream<Arguments> invalidBasicFileInfos() {
        String docxContentType =
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

        return Stream.of(
                // 파일명: null, 빈 문자열, 공백
                Arguments.of(null, 1024L, docxContentType),
                Arguments.of("", 1024L, docxContentType),
                Arguments.of("   ", 1024L, docxContentType),

                // 파일명: 256자 — 허용 최대 길이는 255자
                Arguments.of(
                        "a".repeat(251) + ".docx",
                        1024L,
                        docxContentType
                ),

                // 파일 크기: null, 0, 음수
                Arguments.of("기획서.docx", null, docxContentType),
                Arguments.of("기획서.docx", 0L, docxContentType),
                Arguments.of("기획서.docx", -1L, docxContentType),

                // 파일 형식: null, 빈 문자열, 공백
                Arguments.of("기획서.docx", 1024L, null),
                Arguments.of("기획서.docx", 1024L, ""),
                Arguments.of("기획서.docx", 1024L, "   ")
        );



    }

    @ParameterizedTest
    @ValueSource(longs = {1, 20L * 1024 * 1024})
    @DisplayName("최소 크기와 최대 허용 크기의 파일은 URL을 발급받는다")
    void issuesUploadUrlAtAllowedSizeBoundaries(long fileSize) throws Exception {
        String uploadUrl = "https://example.invalid/upload";
        String storageKey =
                "workspaces/" + workspace.getId() + "/uploads/boundary-test";

        when(uploadUrlGenerator.generate(workspace.getId()))
                .thenReturn(new GeneratedUploadUrl(
                        uploadUrl,
                        storageKey,
                        Instant.parse("2030-01-01T00:00:00Z")
                ));

        CreateUploadUrlRequest request = new CreateUploadUrlRequest(
                "기획서.docx",
                fileSize,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", member.getIssuer())
                                .subject(member.getSubject())))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value(uploadUrl));

        verify(uploadUrlGenerator).generate(workspace.getId());
    }


    @Test
    @DisplayName("비로그인 사용자는 업로드 URL을 발급받을 수 없다")
    void rejectsUploadUrlForUnauthenticatedUser() throws Exception {
        CreateUploadUrlRequest request = new CreateUploadUrlRequest(
                "기획서.docx",
                1024L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(uploadUrlGenerator);
    }



    @Test
    @DisplayName("CSRF 토큰이 없으면 업로드 URL 발급을 거부한다")
    void rejectsUploadUrlWithoutCsrfToken() throws Exception {
        CreateUploadUrlRequest request = new CreateUploadUrlRequest(
                "기획서.docx",
                1024L,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/uploads",
                        workspace.getId()
                )
//                        .with(csrf().asHeader())
                        .with(oidcLogin().idToken(token -> token
                                .claim("iss", member.getIssuer())
                                .subject(member.getSubject())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))

                .andExpect(status().isForbidden());

        verifyNoInteractions(uploadUrlGenerator);
    }










}