package withoutjjin.withoutjjin.domain.document.worker;

import org.junit.jupiter.api.Test;
import withoutjjin.withoutjjin.domain.document.storage.S3ObjectDeleter;
import withoutjjin.withoutjjin.domain.document.worker.S3ObjectDeletionTransactions.PreparedDeletion;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class S3ObjectDeletionWorkerTest {

    private final S3ObjectDeletionTransactions transactions = mock(S3ObjectDeletionTransactions.class);
    private final S3ObjectDeleter deleter = mock(S3ObjectDeleter.class);
    private final S3ObjectDeletionWorker worker = new S3ObjectDeletionWorker(transactions, deleter);

    @Test
    void preservesDeletionFailureWhenRetryRecordingAlsoFails() {
        PreparedDeletion prepared = new PreparedDeletion(1L, "workspaces/10/uploads/original", "token");
        RuntimeException deletionFailure = new IllegalStateException("S3 삭제 실패");
        RuntimeException retryFailure = new IllegalStateException("재시도 시각 저장 실패");
        when(transactions.claim(1L)).thenReturn(Optional.of(prepared));
        doThrow(deletionFailure).when(deleter).delete(prepared.objectKey());
        doThrow(retryFailure).when(transactions).retry(prepared);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(deletionFailure);

        assertThat(deletionFailure.getSuppressed()).containsExactly(retryFailure);
        verify(transactions, never()).complete(any());
    }

    @Test
    void doesNotDeleteOrChangeRetryWhenClaimFails() {
        RuntimeException failure = new IllegalStateException("작업 잠금 실패");
        when(transactions.claim(1L)).thenThrow(failure);

        assertThatThrownBy(() -> worker.process(1L)).isSameAs(failure);

        verifyNoInteractions(deleter);
        verify(transactions, never()).retry(any());
        verify(transactions, never()).complete(any());
    }
}
