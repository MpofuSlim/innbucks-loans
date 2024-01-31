package zw.co.reikan.loans.core.ndasenda;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

import java.util.Map;
import java.util.UUID;

import static zw.co.reikan.loans.core.Utils.right;

@Slf4j
@Service
public class DummyLoanApprovalServiceImpl implements LoanApprovalService {

    Map<String, String> responseCodes = Map.of("11", "Inaccurate Information"
            , "12", "High Debt-to-Income Ratio",
            "13", "Low Credit Score",
            "14", "Regulatory Compliance Issues");

    @Override
    public LoanApprovalResponse requestApproval(LoanApprovalRequest loanRequest) {

        log.info("Processing SSB loan request: {}", loanRequest);

        final String rightMostString = right(loanRequest.getEcnumber(), 2);

        if (responseCodes.containsKey(rightMostString)) {
            return LoanApprovalResponse.builder()
                    .message(responseCodes.get(rightMostString))
                    .reference(UUID.randomUUID().toString())
                    .status(LoanApprovalStatus.REJECTED)
                    .build();
        }

        return LoanApprovalResponse.builder()
                .reference(UUID.randomUUID().toString())
                .message("Approved")
                .status(LoanApprovalStatus.APPROVED)
                .build();
    }
}
