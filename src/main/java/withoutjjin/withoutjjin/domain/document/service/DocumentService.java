package withoutjjin.withoutjjin.domain.document.service;


import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlResponse;
import withoutjjin.withoutjjin.domain.document.storage.GeneratedUploadUrl;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadUrlGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 파일 하나당 용량

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final WorkspaceService workspaceService;
    private final S3UploadUrlGenerator uploadUrlGenerator;




    @Transactional(readOnly = true)
    public CreateUploadUrlResponse createUploadUrl(
            Long workspaceId,
            String issuer,
            String subject,
            CreateUploadUrlRequest request) {


        // 회원·워크스페이스 존재와 소속을 공통 메서드에서 확인
        Workspace workspace = workspaceService.getWorkSpaceForMember(
                workspaceId, issuer, subject
        );

        validateUploadRequest(request); // 파일에 맞는 형식이 올라가는지 확인


        // 검증을 통과한 워크스페이스의 업로드 URL 생성
        GeneratedUploadUrl generated =
                uploadUrlGenerator.generate(workspace.getId());

        // 내부 생성 결과를 API 응답 DTO로 변환
        return new CreateUploadUrlResponse(
                generated.uploadUrl(),
                generated.storageKey(),
                generated.expiresAt()
        );
    }


    private void validateUploadRequest(CreateUploadUrlRequest request) {
        if (request.fileSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "파일 크기는 20MiB 이하여야 합니다."
            );
        }

        if (!request.fileName()
                .toLowerCase(Locale.ROOT)
                .endsWith(".docx")) { // .DOCX  -> .docx로 인식하도록 소문자로 바꾸는 코드
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "docx 파일만 업로드할 수 있습니다."
            );
        }

        if (!DOCX_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "지원하지 않는 파일 형식입니다."
            );
        }
    }



}
