package zw.co.reikan.nanoloansweb.ssb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.SsbApprovalRequest;
import zw.co.reikan.nanoloansweb.SsbResponse;
import zw.co.reikan.nanoloansweb.SsbService;
import zw.co.reikan.nanoloansweb.loan.SsbStatus;

import java.util.Map;

@Slf4j
@Service
public class DummySsbServiceImpl implements SsbService {

    Map<String, String> responseCodes = Map.of("11", "Inaccurate Information"
            , "12", "High Debt-to-Income Ratio",
            "13", "Low Credit Score",
            "14", "Regulatory Compliance Issues");

    private String right(String input, int length) {
        if (input.length() >= length) {
            return input.substring(input.length() - length);
        }
        return input;
    }

    @Override
    public SsbResponse process(SsbApprovalRequest loanRequest) {

        log.info("Processing SSB loan request: {}", loanRequest);

        final String rightMostString = right(loanRequest.getEcnumber(), 2);

        if (responseCodes.containsKey(rightMostString)) {
            return SsbResponse.builder()
                    .message(responseCodes.get(rightMostString))
                    .status(SsbStatus.REJECTED)
                    .build();
        }

        return SsbResponse.builder()
                .message("Approved")
                .status(SsbStatus.APPROVED)
                .build();
    }
}
