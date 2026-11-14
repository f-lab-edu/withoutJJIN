package withoutjjin.withoutjjin.domain.document.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUploadStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.document.repository.DocumentRepository;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
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
    private final DocumentConversionJobRepository conversionJobs;
    private final AppUserService appUserService;
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final EntityManager entityManager;

    public DocumentUploadCompletionTransactions(
            DocumentUploadRepository uploads,
            DocumentRepository documents,
            DocumentVersionRepository versions,
            DocumentConversionJobRepository conversionJobs,
            AppUserService appUserService,
            WorkspaceService workspaceService,
            @Qualifier("documentUploadClock") Clock clock,
            EntityManager entityManager
    ) {
        this.uploads = uploads;
        this.documents = documents;
        this.versions = versions;
        this.conversionJobs = conversionJobs;
        this.appUserService = appUserService;
        this.workspaceService = workspaceService;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PreparedUpload prepare(Long uploadId, String issuer, String subject) {
        DocumentUpload upload = findAuthorizedUpload(
                uploadId,
                issuer, subject,
                false);

        if (upload.getStatus() == DocumentUploadStatus.COMPLETED) {  //이미 완료상태
            // 이미 완료한 업로드는 S3를 다시 검증하지 않고 기존 처리 결과만 반환한다.
            return new PreparedUpload(
                    null,
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
                documentId(upload),
                null);

    }

    // 다른 완료 요청의 커밋과 새 버전 번호를 잠금 획득 후 조회한다.
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
                || !Objects.equals(originalFile.getMediaType(), prepared.mediaType())
                || !Objects.equals(documentId(upload), prepared.documentId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "S3 검증 중 DocumentUpload의 원본 파일 또는 Document가 변경되었습니다.");
        }

        Document document;
        if (upload.getDocument() == null) {
            document = documents.save(new Document(originalFile.getFileName(), 0,
                    upload.getCreatedBy(), null, upload.getWorkspace()));
        } else {
            document = documents.findByIdForUpdate(upload.getDocument().getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document를 찾을 수 없습니다."));
            // 같은 영속성 컨텍스트에서 미리 읽었어도 잠금 이후의 최신 revision을 사용한다.
            entityManager.refresh(document, LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(document.getWorkspace().getId(), upload.getWorkspace().getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "DocumentUpload와 Document의 워크스페이스가 다릅니다.");
            }
        }
        if (document.getRevision() == Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document의 버전 번호 상한을 초과했습니다.");
        }
        int nextVersionNumber = document.getRevision() + 1;
        DocumentVersion version = versions.save(new DocumentVersion(
                nextVersionNumber,
                "",
                null,
                document,
                originalFile.getObjectKey(),
                originalFile.getFileName(),
                originalFile.getSizeBytes(),
                upload.getCreatedBy()));

        document.updateRevision(nextVersionNumber);
        upload.complete(document);
        // Document·DocumentVersion 등록과 업로드 완료 상태를 변환 대기 작업과 함께 커밋한다.
        conversionJobs.save(new DocumentConversionJob(upload, version));

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
        // prepare 또는 상위 트랜잭션에서 읽은 PENDING 상태가 남아 있어도 최신 행 상태로 검사한다.
        if (forUpdate) entityManager.refresh(upload, LockModeType.PESSIMISTIC_WRITE);
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
        if (upload.getDocument() != null && !Objects.equals(
                upload.getWorkspace().getId(), upload.getDocument().getWorkspace().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DocumentUpload와 Document의 워크스페이스가 다릅니다.");
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
        DocumentVersion version = conversionJobs.findByDocumentUploadId(upload.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "COMPLETED 상태의 DocumentUpload에 DocumentConversionJob이 없습니다."))
                .getDocumentVersion();
        if (!Objects.equals(version.getDocument().getId(), upload.getDocument().getId())) {
            throw new IllegalStateException("DocumentUpload와 DocumentVersion의 Document가 다릅니다.");
        }
        return new CompleteUploadResponse(
                upload.getId(), upload.getDocument().getId(), version.getId());
    }

    private Long documentId(DocumentUpload upload) {
        return upload.getDocument() == null ? null : upload.getDocument().getId();
    }

    public record PreparedUpload(
            Long originalFileId,
            String objectKey,
            Long sizeBytes,
            String mediaType,
            Long documentId,
            CompleteUploadResponse completedResponse
    ) {
    }
}
