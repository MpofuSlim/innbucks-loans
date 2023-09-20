package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.nanoloansweb.loan.LoanStatus;

@Data
@Builder
public class LoanResponse {
    private String message;
    private LoanStatus loanStatus;
}
