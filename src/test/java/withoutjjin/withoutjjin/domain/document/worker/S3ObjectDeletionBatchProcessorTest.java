package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import withoutjjin.withoutjjin.domain.document.repository.S3ObjectDeletionTaskRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class S3ObjectDeletionBatchProcessorTest {

    private final S3ObjectDeletionTaskRepository tasks = mock(S3ObjectDeletionTaskRepository.class);
    private final S3ObjectDeletionWorker worker = mock(S3ObjectDeletionWorker.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final S3ObjectDeletionBatchProcessor processor = new S3ObjectDeletionBatchProcessor(tasks, worker, clock);

    @Test
    void processesLimitedReadyTasksAndContinuesAfterFailureWithoutLoggingFileNames(CapturedOutput output) {
        when(tasks.findReadyIds(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));
        when(worker.process(1L)).thenThrow(new IllegalStateException("private-document.docx"));
        when(worker.process(2L)).thenReturn(false);
        when(worker.process(3L)).thenReturn(true);

        assertThat(processor.processPendingBatch(3)).isEqualTo(1);

        InOrder order = inOrder(tasks, worker);
        order.verify(tasks).findReadyIds(LocalDateTime.now(clock), PageRequest.of(0, 3));
        order.verify(worker).process(1L);
        order.verify(worker).process(2L);
        order.verify(worker).process(3L);
        order.verifyNoMoreInteractions();
        assertThat(output.getAll()).contains("taskId=1", "errorType=IllegalStateException")
                .doesNotContain("private-document.docx");
    }

    @Test
    void doesNotInvokeWorkerForEmptyBatch() {
        when(tasks.findReadyIds(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());
        assertThat(processor.processPendingBatch(5)).isZero();
        verifyNoInteractions(worker);
    }

    @Test
    void rejectsInvalidBatchSizesBeforeQuerying() {
        assertThatThrownBy(() -> processor.processPendingBatch(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.processPendingBatch(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks, worker);
    }
}
