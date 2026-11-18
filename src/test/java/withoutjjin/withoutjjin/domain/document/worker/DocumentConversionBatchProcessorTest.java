package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class DocumentConversionBatchProcessorTest {

    private final DocumentConversionJobRepository jobs = mock(DocumentConversionJobRepository.class);
    private final DocumentConversionWorker worker = mock(DocumentConversionWorker.class);
    private final DocumentConversionBatchProcessor processor = new DocumentConversionBatchProcessor(jobs, worker);

    @Test
    @DisplayName("첫 페이지를 지정한 건수로 조회하고 순서대로 실행하여 완료 건수만 반환한다")
    void processesLimitedFirstPageInOrderAndCountsOnlyCompletedJobs() {
        when(jobs.findPendingIds(any(Pageable.class))).thenReturn(List.of(30L, 10L, 20L));
        when(worker.process(30L)).thenReturn(true);
        when(worker.process(10L)).thenReturn(false);
        when(worker.process(20L)).thenReturn(true);

        assertThat(processor.processPendingBatch(3)).isEqualTo(2);

        var order = inOrder(jobs, worker);
        order.verify(jobs).findPendingIds(PageRequest.of(0, 3));
        order.verify(worker).process(30L);
        order.verify(worker).process(10L);
        order.verify(worker).process(20L);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("대기 작업이 없으면 Worker를 호출하지 않고 0을 반환한다")
    void returnsZeroForEmptyBatch() {
        when(jobs.findPendingIds(any(Pageable.class))).thenReturn(List.of());

        assertThat(processor.processPendingBatch(5)).isZero();

        verifyNoInteractions(worker);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    @DisplayName("첫 번째·중간·마지막 작업이 실패해도 나머지 작업을 모두 처리한다")
    void continuesAfterIndividualJobFailure(long failedJobId) {
        when(jobs.findPendingIds(any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));
        when(worker.process(anyLong())).thenReturn(true);
        when(worker.process(failedJobId)).thenThrow(new IllegalStateException("변환 실패"));

        assertThat(processor.processPendingBatch(3)).isEqualTo(2);

        var order = inOrder(worker);
        order.verify(worker).process(1L);
        order.verify(worker).process(2L);
        order.verify(worker).process(3L);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("실패한 작업 ID와 오류 종류를 기록하고 예외 메시지는 기록하지 않는다")
    void logsJobIdAndErrorTypeWithoutDocumentContent(CapturedOutput output) {
        when(jobs.findPendingIds(any(Pageable.class))).thenReturn(List.of(2L));
        when(worker.process(2L)).thenThrow(new IllegalStateException("로그에 남기면 안 되는 문서 내용"));

        assertThat(processor.processPendingBatch(1)).isZero();

        assertThat(output.getAll()).contains("jobId=2", "errorType=IllegalStateException")
                .doesNotContain("로그에 남기면 안 되는 문서 내용");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("조회 건수가 양수가 아니면 DB 조회와 작업 실행을 하지 않는다")
    void rejectsNonPositiveBatchSize(int batchSize) {
        assertThatThrownBy(() -> processor.processPendingBatch(batchSize))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("양수");

        verifyNoInteractions(jobs, worker);
    }

    @Test
    @DisplayName("대기 작업 조회 자체가 실패하면 Worker를 호출하지 않고 예외를 전달한다")
    void propagatesPendingQueryFailure() {
        var failure = new DataAccessResourceFailureException("대기 작업 조회 실패");
        when(jobs.findPendingIds(any(Pageable.class))).thenThrow(failure);

        assertThatThrownBy(() -> processor.processPendingBatch(5)).isSameAs(failure);

        verifyNoInteractions(worker);
    }
}
