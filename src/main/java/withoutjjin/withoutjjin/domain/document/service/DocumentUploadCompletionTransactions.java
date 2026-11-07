package withoutjjin.withoutjjin.domain.document.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentUploadRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentVersionRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class DocumentUploadCompletionTransactions {

    private final DocumentUploadRepository uploads;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    public DocumentUploadCompletionTransactions(
            DocumentUploadRepository uploads,
            DocumentRepository documents,
            DocumentVersionRepository versions,
            AppUserService appUserService,
            WorkspaceService workspaceService,
            @Qualifier("documentUploadClock") Clock clock
    ) {
        this.uploads = uploads;
        this.documents = documents;
        this.versions = versions;
        this.appUserService = appUserService;
        this.workspaceService = workspaceService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreparedUpload prepare(Long uploadId, String issuer, String subject) {
        DocumentUpload upload = findAuthorizedUpload(
                uploadId,
                issuer, subject,
                false);

        if (upload.getStatus() == DocumentUploadStatus.COMPLETED) {  //이미 완료상태
            // 이미 완료했으므로 S3를 다시 검증할 필요가 없어. 따라서 앞의 네 필드는 사용하지 않고,
            // completedResponse에 기존 처리 결과를 담아.
            return new PreparedUpload(
                    null,
                    null,
                    null,
                    null,
                    completedResponse(upload));
        }

        StoreFile originalFile = validatePendingUpload(upload);
        // 엔티티 대신 스칼라 값을 반환하여 트랜잭션 밖에서 지연 로딩하지 않는다.
        return new PreparedUpload(
                originalFile.getId(),
                originalFile.getObjectKey(),
                originalFile.getSizeBytes(),
                originalFile.getMediaType(),
                null);

    }

    // 잠금 대기 전에 시작된 이전 조회 시점 때문에 첫 DocumentVersion을 놓치지 않도록 한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CompleteUploadResponse complete(
            Long uploadId, String issuer, String subject, PreparedUpload prepared) {
        DocumentUpload upload = findAuthorizedUpload(uploadId, issuer, subject, true);
        // 다른 완료 요청이 먼저 커밋했다면 Document를 다시 생성하지 않는다.
        if (upload.getStatus() == DocumentUploadStatus.COMPLETED) {
            return completedResponse(upload);
        }
        StoreFile originalFile = validatePendingUpload(upload);
        if (!Objects.equals(originalFile.getId(), prepared.originalFileId())
                || !Objects.equals(originalFile.getObjectKey(), prepared.objectKey())
                || !Objects.equals(originalFile.getSizeBytes(), prepared.sizeBytes())
                || !Objects.equals(originalFile.getMediaType(), prepared.mediaType())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "S3 검증 중 DocumentUpload.originalFile이 변경되었습니다.");
        }

        Document document = documents.save(new Document(
                originalFile.getFileName(),
                0,
                upload.getCreatedBy(),
                null,
                upload.getWorkspace()));

        DocumentVersion version = versions.save(new DocumentVersion(
                1,
                "",
                null,
                document,
                originalFile.getObjectKey(),
                originalFile.getFileName(),
                originalFile.getSizeBytes(),
                upload.getCreatedBy()));

        document.updateRevision(1);
        upload.complete(document);

        return new CompleteUploadResponse(upload.getId(), document.getId(), version.getId());
    }

    private DocumentUpload findAuthorizedUpload(
            Long uploadId, String issuer, String subject, boolean forUpdate) {
        if (uploadId == null || uploadId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploadId는 양수여야 합니다.");
        }
        AppUser requester = appUserService.getByIssuerAndSubject(issuer, subject);
        DocumentUpload upload = (forUpdate
                ? uploads.findByIdForUpdate(uploadId) : uploads.findById(uploadId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "DocumentUpload를 찾을 수 없습니다."));
        if (!upload.getCreatedBy().getId().equals(requester.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "DocumentUpload 요청자만 완료할 수 있습니다.");
        }
        workspaceService.getWorkSpaceForMember(upload.getWorkspace().getId(), requester);
        return upload;
    }

    private StoreFile validatePendingUpload(DocumentUpload upload) {
        if (upload.getStatus() != DocumentUploadStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "PENDING 상태의 DocumentUpload만 완료할 수 있습니다.");
        }
        if (!upload.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.GONE, "DocumentUpload가 만료되었습니다.");
        }
        if (upload.getDocument() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "기존 Document의 새 버전 업로드는 지원하지 않습니다.");
        }
        if (upload.getOriginalFile() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "DocumentUpload.originalFile이 없습니다.");
        }

        return upload.getOriginalFile();
    }

    private CompleteUploadResponse completedResponse(DocumentUpload upload) {
        if (upload.getDocument() == null) {
            throw new IllegalStateException("COMPLETED 상태의 DocumentUpload에 Document가 없습니다.");
        }
        DocumentVersion version = versions
                .findByDocumentIdAndVersionNumber(upload.getDocument().getId(), 1)
                .orElseThrow(() -> new IllegalStateException(
                        "COMPLETED 상태의 DocumentUpload에 첫 DocumentVersion이 없습니다."));
        return new CompleteUploadResponse(
                upload.getId(), upload.getDocument().getId(), version.getId());
    }

    public record PreparedUpload(
            Long originalFileId,
            String objectKey,
            Long sizeBytes,
            String mediaType,
            CompleteUploadResponse completedResponse
    ) {
    }
}
