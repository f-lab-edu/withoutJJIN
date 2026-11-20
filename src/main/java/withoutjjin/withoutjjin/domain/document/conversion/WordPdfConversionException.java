package withoutjjin.withoutjjin.domain.document.conversion;

import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;

import java.util.Objects;

public class WordPdfConversionException extends RuntimeException {

    private final DocumentConversionFailureCode failureCode;

    public WordPdfConversionException(String message) {
        this(DocumentConversionFailureCode.CONVERSION_FAILED, message, null);
    }

    public WordPdfConversionException(String message, Throwable cause) {
        this(DocumentConversionFailureCode.CONVERSION_FAILED, message, cause);
    }

    public WordPdfConversionException(DocumentConversionFailureCode failureCode, String message) {
        this(failureCode, message, null);
    }

    public WordPdfConversionException(DocumentConversionFailureCode failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = Objects.requireNonNull(failureCode, "변환 실패 원인 코드가 필요합니다.");
    }

    public DocumentConversionFailureCode getFailureCode() {
        return failureCode;
    }
}
