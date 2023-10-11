package zw.co.reikan.loans.core.ndasenda;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
public class LoanApprovalRequest implements Serializable {
    private String ecnumber;
    private String reference;
    private String name;
    private String surname;
    private String payrollNumber;
    private String idNumber;
    private BigDecimal monthlyInstallment;
    private long tenor;
}
