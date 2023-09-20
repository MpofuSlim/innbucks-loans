package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoanResponse {
    private String message;
    private LoanStatus loanStatus;
}
