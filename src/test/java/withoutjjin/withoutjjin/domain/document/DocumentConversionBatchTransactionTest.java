package withoutjjin.withoutjjin.domain.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import withoutjjin.withoutjjin.domain.document.repository.DocumentConversionJobRepository;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionBatchProcessor;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionWorker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class DocumentConversionBatchTransactionTest {

    @Autowired private DocumentConversionBatchProcessor processor;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private DocumentConversionJobRepository jobs;
    @MockitoBean private DocumentConversionWorker worker;

    @Test
    @DisplayName("대기 작업 조회와 Worker 호출을 트랜잭션 없이 실행한다")
    void queriesAndInvokesWorkerOutsideTransaction() {
        when(jobs.findPendingIds(PageRequest.of(0, 5))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(1L);
        });
        when(worker.process(1L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return true;
        });

        assertThat(processor.processPendingBatch(5)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 열린 트랜잭션 안에서 배치를 호출하면 DB 조회 전에 거부한다")
    void rejectsBatchInsideCallerTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThatThrownBy(() -> processor.processPendingBatch(5))
                    .isInstanceOf(IllegalTransactionStateException.class);
        });

        verifyNoInteractions(jobs, worker);
    }
}
