package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        doThrow(statusFailure).when(transactions).markFailed(1L);

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
        verify(transactions, never()).markFailed(any());
        verify(transactions, never()).complete(any(), any());
    }
}
