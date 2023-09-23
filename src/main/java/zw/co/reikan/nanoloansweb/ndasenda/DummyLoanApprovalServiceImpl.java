package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.loan.SsbStatus;

import java.util.Map;
import java.util.UUID;

import static zw.co.reikan.nanoloansweb.Utils.right;

@Slf4j
@Service
public class DummyLoanApprovalServiceImpl implements LoanApprovalService {

    Map<String, String> responseCodes = Map.of("11", "Inaccurate Information"
            , "12", "High Debt-to-Income Ratio",
            "13", "Low Credit Score",
            "14", "Regulatory Compliance Issues");

    @Override
    public SsbResponse process(LoanApprovalRequest loanRequest) {

        log.info("Processing SSB loan request: {}", loanRequest);

        final String rightMostString = right(loanRequest.getEcnumber(), 2);

        if (responseCodes.containsKey(rightMostString)) {
            return SsbResponse.builder()
                    .message(responseCodes.get(rightMostString))
                    .reference(UUID.randomUUID().toString())
                    .status(SsbStatus.REJECTED)
                    .build();
        }

        return SsbResponse.builder()
                .reference(UUID.randomUUID().toString())
                .message("Approved")
                .status(SsbStatus.APPROVED)
                .build();
    }
}
