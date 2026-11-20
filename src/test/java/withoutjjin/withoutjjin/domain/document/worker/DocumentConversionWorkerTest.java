package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import withoutjjin.withoutjjin.domain.document.conversion.DocumentConversionException;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureCode;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionService;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionTransactions.PreparedConversion;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DocumentConversionWorkerTest {

    private final DocumentConversionTransactions transactions = mock(DocumentConversionTransactions.class);
    private final DocumentConversionService conversionService = mock(DocumentConversionService.class);
    private final DocumentConversionWorker worker = new DocumentConversionWorker(transactions, conversionService);

    @Test
    @DisplayName("실패 상태 저장까지 실패해도 최초 변환 오류와 추가 원인을 함께 전달한다")
    void preservesOriginalFailureWhenFailureRecordingAlsoFails() {
        PreparedConversion prepared = new PreparedConversion(1L, 10L, 20L, 30L, "workspaces/10/uploads/source");
        RuntimeException conversionFailure = new IllegalStateException("PDF 변환 실패");
        RuntimeException statusFailure = new IllegalStateException("실패 상태 저장 실패");
        when(transactions.claim(1L)).thenReturn(Optional.of(prepared));
        when(conversionService.convertAndStore(10L, prepared.originalObjectKey())).thenThrow(conversionFailure);
        doThrow(statusFailure).when(transactions).markFailed(1L,
                DocumentConversionFailureStage.UNKNOWN, DocumentConversionFailureCode.UNKNOWN);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(conversionFailure);

        assertThat(conversionFailure.getSuppressed()).containsExactly(statusFailure);
        verify(transactions, never()).complete(any(), any());
    }

    @Test
    @DisplayName("작업 시작 트랜잭션이 실패하면 변환하거나 실패 상태를 덮어쓰지 않는다")
    void doesNotProcessJobWhenClaimFails() {
        RuntimeException failure = new IllegalStateException("작업 잠금 실패");
        when(transactions.claim(1L)).thenThrow(failure);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(failure);

        verifyNoInteractions(conversionService);
        verify(transactions, never()).markFailed(any(), any(), any());
        verify(transactions, never()).complete(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentConversionFailureStage.class, names = {"ORIGINAL_DOWNLOAD", "PDF_CONVERSION", "PDF_UPLOAD"})
    @DisplayName("변환 서비스가 전달한 실패 단계와 코드를 독립 트랜잭션에 전달한다")
    void recordsStageReportedByConversionService(DocumentConversionFailureStage stage) {
        PreparedConversion prepared = new PreparedConversion(1L, 10L, 20L, 30L, "workspaces/10/uploads/source");
        DocumentConversionException failure = DocumentConversionException.from(stage, new IllegalStateException("private"));
        when(transactions.claim(1L)).thenReturn(Optional.of(prepared));
        when(conversionService.convertAndStore(10L, prepared.originalObjectKey())).thenThrow(failure);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(failure);

        verify(transactions).markFailed(1L, stage, DocumentConversionFailureCode.UNKNOWN);
        verify(transactions, never()).complete(any(), any());
    }

    @Test
    @DisplayName("변환 결과 DB 저장 실패는 RESULT_SAVE 단계로 기록하고 최초 예외를 전달한다")
    void recordsDatabaseResultFailure() {
        PreparedConversion prepared = new PreparedConversion(1L, 10L, 20L, 30L, "workspaces/10/uploads/source");
        StoredPdf pdf = new StoredPdf("workspaces/10/review-pdfs/result.pdf", 1024L, 2);
        RuntimeException failure = new DataIntegrityViolationException("private SQL details");
        when(transactions.claim(1L)).thenReturn(Optional.of(prepared));
        when(conversionService.convertAndStore(10L, prepared.originalObjectKey())).thenReturn(pdf);
        doThrow(failure).when(transactions).complete(prepared, pdf);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(failure);

        verify(transactions).markFailed(1L, DocumentConversionFailureStage.RESULT_SAVE,
                DocumentConversionFailureCode.RESULT_SAVE_FAILED);
    }
}
