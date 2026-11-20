package withoutjjin.withoutjjin.domain.document.entity;

// 예외 메시지 대신 저장하는 고정 코드다. 실패 단계와 함께 원인을 확인한다.
public enum DocumentConversionFailureCode {
    ACCESS_DENIED,
    FILE_NOT_FOUND,
    CONNECTION_FAILED,
    TIMEOUT,
    CONVERSION_FAILED,
    INVALID_PDF,
    SIZE_LIMIT_EXCEEDED,
    RESULT_SAVE_FAILED,
    UNKNOWN
}
