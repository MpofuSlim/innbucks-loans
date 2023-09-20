package zw.co.reikan.nanoloansweb.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.DisbursementRequest;
import zw.co.reikan.nanoloansweb.DisbursementResponse;
import zw.co.reikan.nanoloansweb.DisbursementService;
import zw.co.reikan.nanoloansweb.loan.DisbursementStatus;

@Slf4j
@Service
public class KinetoServiceImpl implements DisbursementService {

    @Override
    public DisbursementResponse disburseFunds(DisbursementRequest request) {
        log.info("Processing loan disbursement: {}", request);

        return DisbursementResponse.builder()
                .status(DisbursementStatus.SUCCESS)
                .message("Success")
                .build();
    }
}
