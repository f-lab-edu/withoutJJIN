package withoutjjin.withoutjjin.domain.document.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import withoutjjin.withoutjjin.domain.document.dto.CompleteUploadResponse;
import withoutjjin.withoutjjin.domain.document.service.DocumentUploadCompletionTransactions.PreparedUpload;
import withoutjjin.withoutjjin.domain.document.storage.S3UploadVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DocumentUploadCompletionServiceTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String SUBJECT = "upload-requester";
    private DocumentUploadCompletionTransactions transactions;
    private S3UploadVerifier verifier;
    private DocumentUploadCompletionService service;
    private PreparedUpload prepared;

    @BeforeEach
    void setUp() {
        transactions = mock(DocumentUploadCompletionTransactions.class);
        verifier = mock(S3UploadVerifier.class);
        service = new DocumentUploadCompletionService(transactions, verifier);
        prepared = new PreparedUpload(
                5L, "workspaces/10/uploads/test", 1024L, "application/docx", null);
        when(transactions.prepare(1L, ISSUER, SUBJECT)).thenReturn(prepared);
    }

    @Test
    @DisplayName("prepare 이후 S3를 검증하고 complete에서 Document 등록 결과를 반환한다")
    void verifiesS3BeforeCompletingUpload() {
        CompleteUploadResponse expected = new CompleteUploadResponse(1L, 2L, 3L);
        when(transactions.complete(1L, ISSUER, SUBJECT, prepared)).thenReturn(expected);

        assertThat(service.completeUpload(1L, ISSUER, SUBJECT)).isEqualTo(expected);

        InOrder order = inOrder(transactions, verifier);
        order.verify(transactions).prepare(1L, ISSUER, SUBJECT);
        order.verify(verifier).verify(prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());
        order.verify(transactions).complete(1L, ISSUER, SUBJECT, prepared);
    }

    @Test
    @DisplayName("이미 완료된 DocumentUpload이면 S3 검증 없이 기존 결과를 반환한다")
    void returnsCompletedResponseWithoutAnotherS3Request() {
        CompleteUploadResponse expected = new CompleteUploadResponse(1L, 2L, 3L);
        when(transactions.prepare(1L, ISSUER, SUBJECT))
                .thenReturn(new PreparedUpload(null, null, null, null, expected));

        assertThat(service.completeUpload(1L, ISSUER, SUBJECT)).isEqualTo(expected);

        verifyNoInteractions(verifier);
        verify(transactions, never()).complete(any(), anyString(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"404,404", "403,502", "500,502", "503,502"})
    @DisplayName("S3 조회 실패를 구분하고 Document 등록을 호출하지 않는다")
    void rejectsS3FailuresWithoutCompleting(int s3Status, int responseStatus) {
        S3Exception failure = (S3Exception) S3Exception.builder()
                .statusCode(s3Status).message("S3 조회 실패").build();
        doThrow(failure).when(verifier)
                .verify(prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());

        assertThatThrownBy(() -> service.completeUpload(1L, ISSUER, SUBJECT))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(responseStatus);
                    assertThat(exception.getCause()).isSameAs(failure);
                });

        verify(transactions, never()).complete(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("S3 통신 실패이면 503 예외를 전달하고 Document 등록을 호출하지 않는다")
    void rejectsConnectionFailureWithoutCompleting() {
        SdkClientException failure = SdkClientException.builder().message("연결 실패").build();
        doThrow(failure).when(verifier)
                .verify(prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());

        assertThatThrownBy(() -> service.completeUpload(1L, ISSUER, SUBJECT))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));

        verify(transactions, never()).complete(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("StoreFile과 S3 응답이 다르면 409 예외를 전달하고 Document 등록을 호출하지 않는다")
    void rejectsMetadataMismatchWithoutCompleting() {
        doThrow(new IllegalStateException("StoreFile.sizeBytes 불일치")).when(verifier)
                .verify(prepared.objectKey(), prepared.sizeBytes(), prepared.mediaType());

        assertThatThrownBy(() -> service.completeUpload(1L, ISSUER, SUBJECT))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));

        verify(transactions, never()).complete(any(), anyString(), anyString(), any());
    }
}
