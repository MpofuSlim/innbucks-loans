package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoanBatchService {
    private final LoanBatchRepository loanBatchRepository;

    public boolean existsByBatchNumber(String batchNumber) {
        if (StringUtils.isEmpty(batchNumber)) {
            return false;
        }
        return loanBatchRepository.existsByBatchNumber(batchNumber);
    }

    public LoanBatch save(String batchNumber) {

        final Optional<LoanBatch> optionalLoanBatch = loanBatchRepository.findByBatchNumber(batchNumber);

        if (optionalLoanBatch.isPresent()) {
            return optionalLoanBatch.get();
        }
        return loanBatchRepository.save(LoanBatch.builder()
                .batchNumber(batchNumber)
                .build());
    }

}
