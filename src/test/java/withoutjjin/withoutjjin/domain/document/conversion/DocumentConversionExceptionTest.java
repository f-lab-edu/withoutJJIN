package withoutjjin.withoutjjin.domain.document.conversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.ResourceAccessException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentConversionExceptionTest {

    @ParameterizedTest
    @MethodSource("failures")
    void classifiesKnownFailuresWithoutUsingExceptionMessage(DocumentConversionFailureStage stage,
                                                            RuntimeException cause, DocumentConversionFailureCode code) {
        DocumentConversionException failure = DocumentConversionException.from(stage, cause);

        assertThat(failure.getFailureStage()).isEqualTo(stage);
        assertThat(failure.getFailureCode()).isEqualTo(code);
        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(failure.getMessage()).doesNotContain("private-document", "private-key", "private SQL");
    }

    static Stream<Arguments> failures() {
        DocumentConversionFailureStage download = DocumentConversionFailureStage.ORIGINAL_DOWNLOAD;
        DocumentConversionFailureStage conversion = DocumentConversionFailureStage.PDF_CONVERSION;
        DocumentConversionFailureStage upload = DocumentConversionFailureStage.PDF_UPLOAD;
        return Stream.of(
                Arguments.of(download, s3(403), DocumentConversionFailureCode.ACCESS_DENIED),
                Arguments.of(upload, s3(403), DocumentConversionFailureCode.ACCESS_DENIED),
                Arguments.of(download, s3(404), DocumentConversionFailureCode.FILE_NOT_FOUND),
                Arguments.of(upload, s3(404), DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(download, (S3Exception) S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build(),
                        DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(download, s3(500), DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(upload, s3(504), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(download, SdkClientException.create("private-key", new ConnectException()),
                        DocumentConversionFailureCode.CONNECTION_FAILED),
                Arguments.of(download, SdkClientException.create("private-key", new UnknownHostException()),
                        DocumentConversionFailureCode.CONNECTION_FAILED),
                Arguments.of(download, SdkClientException.create("private-key"), DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(conversion, new IllegalStateException("AccessDenied Timeout private-document"),
                        DocumentConversionFailureCode.UNKNOWN),
                Arguments.of(conversion, new WordPdfConversionException("private-document"),
                        DocumentConversionFailureCode.CONVERSION_FAILED),
                Arguments.of(conversion, new WordPdfConversionException(DocumentConversionFailureCode.INVALID_PDF,
                        "private-document", new IOException()), DocumentConversionFailureCode.INVALID_PDF),
                Arguments.of(conversion, new WordPdfConversionException(DocumentConversionFailureCode.SIZE_LIMIT_EXCEEDED,
                        "private-document"), DocumentConversionFailureCode.SIZE_LIMIT_EXCEEDED),
                Arguments.of(conversion, new WordPdfConversionException("private-document",
                        new ResourceAccessException("private-key", new ConnectException())), DocumentConversionFailureCode.CONNECTION_FAILED),
                Arguments.of(conversion, new WordPdfConversionException("private-document",
                        new ResourceAccessException("private-key", new SocketTimeoutException())), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(conversion, new WordPdfConversionException("private-document",
                        new ResourceAccessException("private-key", new HttpTimeoutException("timeout"))), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(download, ApiCallTimeoutException.create(1000), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(upload, ApiCallAttemptTimeoutException.create(1000), DocumentConversionFailureCode.TIMEOUT),
                Arguments.of(DocumentConversionFailureStage.RESULT_SAVE, new DataIntegrityViolationException("private SQL"),
                        DocumentConversionFailureCode.RESULT_SAVE_FAILED));
    }

    @Test
    void handlesCyclicCauseWithoutInfiniteLoop() {
        RuntimeException cyclic = new RuntimeException("private-document") {
            @Override
            public synchronized Throwable getCause() { return this; }
        };

        assertThat(DocumentConversionException.from(DocumentConversionFailureStage.UNKNOWN, cyclic).getFailureCode())
                .isEqualTo(DocumentConversionFailureCode.UNKNOWN);
    }

    private static S3Exception s3(int status) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("private-document").build();
    }
}
