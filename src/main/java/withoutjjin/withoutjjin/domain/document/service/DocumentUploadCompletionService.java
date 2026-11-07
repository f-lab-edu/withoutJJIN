package withoutjjin.withoutjjin.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadVerifier;

@Service
@RequiredArgsConstructor
public class DocumentUploadCompletionService {

    private final DocumentUploadCompletionTransactions transactions;
    private final S3UploadVerifier s3UploadVerifier;

    // 외부 S3 요청 중에는 호출자의 DB 트랜잭션도 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompleteUploadResponse completeUpload(
            Long uploadId, String issuer, String subject) {
        var prepared = transactions.prepare(uploadId, issuer, subject);
        if (prepared.completedResponse() != null) {
            return prepared.completedResponse();
        }

        try {
            s3UploadVerifier.verify(
                    prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());
        } catch (S3Exception exception) {
            HttpStatus status = exception.statusCode() == 404
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(
                    status, "S3 업로드 파일을 확인할 수 없습니다.", exception);
        } catch (SdkClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "S3 연결에 실패했습니다. 잠시 후 다시 시도해 주세요.", exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "S3 파일 정보가 StoreFile과 일치하지 않습니다.", exception);
        }

        return transactions.complete(uploadId, issuer, subject, prepared);
    }
}
