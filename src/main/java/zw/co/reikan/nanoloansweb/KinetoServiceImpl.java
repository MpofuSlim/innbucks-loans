package zw.co.reikan.nanoloansweb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
