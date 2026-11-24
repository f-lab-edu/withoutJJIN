package withoutjjin.withoutjjin.domain.document.conversion;

import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

// 원본 예외는 cause로 전달하되 DB에는 단계와 고정 코드만 저장한다.
public class DocumentConversionException extends RuntimeException {

    private final DocumentConversionFailureStage failureStage;
    private final DocumentConversionFailureCode failureCode;

    private DocumentConversionException(DocumentConversionFailureStage stage,
                                        DocumentConversionFailureCode code, RuntimeException cause) {
        super("PDF 변환 처리 실패: " + stage + " / " + code, cause);
        this.failureStage = stage;
        this.failureCode = code;
    }

    public static DocumentConversionException from(DocumentConversionFailureStage stage, RuntimeException cause) {
        Objects.requireNonNull(stage, "실패 단계가 필요합니다.");
        Objects.requireNonNull(cause, "원본 예외가 필요합니다.");
        return new DocumentConversionException(stage, classify(stage, cause), cause);
    }

    public DocumentConversionFailureStage getFailureStage() {
        return failureStage;
    }

    public DocumentConversionFailureCode getFailureCode() {
        return failureCode;
    }

    private static DocumentConversionFailureCode classify(DocumentConversionFailureStage stage, Throwable failure) {
        if (stage == DocumentConversionFailureStage.RESULT_SAVE) {
            return DocumentConversionFailureCode.RESULT_SAVE_FAILED;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        DocumentConversionFailureCode declared = null;
        boolean communicationFailure = false;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            DocumentConversionFailureCode known = knownCode(stage, cause);
            if (known != null) return known;
            if (cause instanceof WordPdfConversionException conversion && declared == null) {
                declared = conversion.getFailureCode();
            }
            communicationFailure |= isCommunicationFailure(cause);
        }
        if (communicationFailure) return DocumentConversionFailureCode.CONNECTION_FAILED;
        return declared == null ? DocumentConversionFailureCode.UNKNOWN : declared;
    }

    private static DocumentConversionFailureCode knownCode(DocumentConversionFailureStage stage, Throwable cause) {
        if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                || cause instanceof ApiCallTimeoutException || cause instanceof ApiCallAttemptTimeoutException) {
            return DocumentConversionFailureCode.TIMEOUT;
        }
        if (cause instanceof S3Exception s3) {
            return switch (s3.statusCode()) {
                case 403 -> DocumentConversionFailureCode.ACCESS_DENIED;
                case 404 -> stage == DocumentConversionFailureStage.ORIGINAL_DOWNLOAD
                        && (s3.awsErrorDetails() == null || !"NoSuchBucket".equals(s3.awsErrorDetails().errorCode()))
                        ? DocumentConversionFailureCode.FILE_NOT_FOUND : null;
                case 408, 504 -> DocumentConversionFailureCode.TIMEOUT;
                default -> null;
            };
        }
        return null;
    }

    private static boolean isCommunicationFailure(Throwable cause) {
        return cause instanceof ResourceAccessException || cause instanceof ConnectException
                || cause instanceof SocketException || cause instanceof UnknownHostException;
    }
}
