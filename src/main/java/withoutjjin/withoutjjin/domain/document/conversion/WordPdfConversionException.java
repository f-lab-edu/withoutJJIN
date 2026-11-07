package withoutjjin.withoutjjin.domain.document.conversion;

public class WordPdfConversionException extends RuntimeException {

    public WordPdfConversionException(String message) {
        super(message);
    }

    public WordPdfConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
