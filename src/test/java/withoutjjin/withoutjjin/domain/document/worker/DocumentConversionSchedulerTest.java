package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DocumentConversionSchedulerTest {

    private final DocumentConversionBatchProcessor processor = mock(DocumentConversionBatchProcessor.class);

    @Test
    @DisplayName("설정한 최대 조회 건수로 배치 처리를 호출한다")
    void invokesBatchWithConfiguredSize() {
        DocumentConversionScheduler scheduler = new DocumentConversionScheduler(processor, 3);

        scheduler.processPendingConversions();

        verify(processor).processPendingBatch(3);
    }

    @Test
    @DisplayName("배치 조회가 실패해도 다음 호출을 처리하며 예외 메시지는 기록하지 않는다")
    void continuesAfterQueryFailureWithoutLoggingDocumentContent(CapturedOutput output) {
        DocumentConversionScheduler scheduler = new DocumentConversionScheduler(processor, 5);
        when(processor.processPendingBatch(5)).thenThrow(new IllegalStateException("공개하면 안 되는 문서 내용"))
                .thenReturn(0);

        assertThatCode(scheduler::processPendingConversions).doesNotThrowAnyException();
        assertThatCode(scheduler::processPendingConversions).doesNotThrowAnyException();

        verify(processor, times(2)).processPendingBatch(5);
        assertThat(output.getAll()).contains("errorType=IllegalStateException")
                .doesNotContain("공개하면 안 되는 문서 내용");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("최대 조회 건수가 양수가 아니면 스케줄러 생성을 거부한다")
    void rejectsNonPositiveBatchSize(int batchSize) {
        assertThatThrownBy(() -> new DocumentConversionScheduler(processor, batchSize))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("조회 건수");

        verifyNoInteractions(processor);
    }
}
