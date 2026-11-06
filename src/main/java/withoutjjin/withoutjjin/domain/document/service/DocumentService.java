package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlRequest;
import withoutjjin.withoutjjin.domain.document.dto.CreateUploadUrlResponse;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.StoreFileRepository;
import withoutjjin.withoutjjin.domain.document.storage.GeneratedUploadUrl;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadUrlGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;
    private final S3UploadUrlGenerator uploadUrlGenerator;
    private final StoreFileRepository storeFileRepository;
    private final DocumentUploadRepository documentUploadRepository;

    // 파일 정보와 업로드 요청을 하나의 트랜잭션으로 저장한다.
    @Transactional
    public CreateUploadUrlResponse createUploadUrl(
            Long workspaceId, String issuer, String subject, CreateUploadUrlRequest request) {
        AppUser requester = appUserService.getByIssuerAndSubject(issuer, subject);
        //로그인 세션ID가 appUser에 실존하는지 확인
        Workspace workspace = workspaceService.getWorkSpaceForMember(workspaceId, requester);
        //멤버가 해당 워크스페이스에 참여하고있는 여부조사
        validateUploadRequest(request);

        // URL 생성에 실패하면 DB에 요청을 남기지 않는다.
        GeneratedUploadUrl generated = uploadUrlGenerator.generate(workspace.getId()); //업로드를 위한 URL생성

        StoreFile originalFile = storeFileRepository.save(
        new StoreFile(
                workspace,
                generated.storageKey(), // S3의 저장소 식별값
                request.fileName(),
                DOCX_CONTENT_TYPE,
                request.fileSize()
        ));

        // 현재는 작업 유효기한을 URL 만료 시각과 같게 기록한다.
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                generated.expiresAt(), ZoneId.of("Asia/Seoul")
        );



        DocumentUpload upload = documentUploadRepository.save(
                new DocumentUpload(
                workspace,
        null,
                requester,
                originalFile,
                request.fileSize(),
                expiresAt
        ));

        return new CreateUploadUrlResponse(
                upload.getId(), generated.uploadUrl(), generated.storageKey(), generated.expiresAt()
        );

    }

    private void validateUploadRequest(CreateUploadUrlRequest request) {
        if (request.fileSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "파일 크기는 20MiB 이하여야 합니다."
            );
        }
        if (!request.fileName().toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "docx 파일만 업로드할 수 있습니다."
            );
        }
        if (!DOCX_CONTENT_TYPE.equalsIgnoreCase(request.contentType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다."
            );
        }
    }
}
