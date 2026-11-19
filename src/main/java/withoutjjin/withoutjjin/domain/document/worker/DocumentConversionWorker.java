package withoutjjin.withoutjjin.domain.document.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionService;

@Component
@RequiredArgsConstructor
public class DocumentConversionWorker {

    private final DocumentConversionTransactions transactions;
    private final DocumentConversionService conversionService;

    // 호출자의 트랜잭션을 중단하고, 각 DB 작업은 transactions의 독립 트랜잭션으로 처리한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean process(Long jobId) {
        var claimed = transactions.claim(jobId);
        if (claimed.isEmpty()) {
            return false;
        }

        var prepared = claimed.get();
        try {
            StoredPdf pdf = conversionService.convertAndStore(prepared.workspaceId(), prepared.originalObjectKey());
            transactions.complete(prepared, pdf);
            return true;
        } catch (RuntimeException failure) {
            try {
                transactions.markFailed(prepared.jobId());
            } catch (RuntimeException statusFailure) {
                // 실패 상태 저장까지 실패해도 최초 변환·저장 오류를 잃지 않는다.
                failure.addSuppressed(statusFailure);
            }
            throw failure;
        }
    }
}
