package withoutjjin.withoutjjin.domain.document.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.document.conversion.StoredPdf;
import withoutjjin.withoutjjin.domain.document.conversion.DocumentConversionException;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionFailureStage;
import withoutjjin.withoutjjin.domain.document.service.DocumentConversionService;
import withoutjjin.withoutjjin.domain.document.worker.DocumentConversionTransactions.PreparedConversion;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DocumentConversionWorker {

    private final DocumentConversionTransactions transactions;
    private final DocumentConversionService conversionService;

    // 호출자의 트랜잭션을 중단하고, 각 DB 작업은 transactions의 독립 트랜잭션으로 처리한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean process(Long jobId) {
        Optional<PreparedConversion> claimed = transactions.claim(jobId);
        if (claimed.isEmpty()) {
            return false;
        }

        PreparedConversion prepared = claimed.get();
        DocumentConversionFailureStage stage = DocumentConversionFailureStage.UNKNOWN;
        try {
            StoredPdf pdf = conversionService.convertAndStore(prepared.workspaceId(), prepared.originalObjectKey());
            stage = DocumentConversionFailureStage.RESULT_SAVE;
            transactions.complete(prepared, pdf);
            return true;
        } catch (RuntimeException failure) {
            DocumentConversionException details = stage != DocumentConversionFailureStage.RESULT_SAVE
                    && failure instanceof DocumentConversionException conversion
                    ? conversion : DocumentConversionException.from(stage, failure);
            try {
                transactions.markFailed(prepared.jobId(), details.getFailureStage(), details.getFailureCode());
            } catch (RuntimeException statusFailure) {
                // 실패 상태 저장까지 실패해도 최초 변환·저장 오류를 잃지 않는다.
                failure.addSuppressed(statusFailure);
            }
            throw failure;
        }
    }
}
