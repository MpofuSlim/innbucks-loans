package zw.co.reikan.loans.core.ndasenda;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * {@code toString} is log-safe: the national ID is left out and the EC number masked,
 * because the Ndasenda lodgement logs this request whole.
 */
@Data
@Builder
public class LoanApprovalRequest implements Serializable {
    @ToString.Exclude
    private String ecnumber;
    private String reference;
    private String name;
    private String surname;
    private String payrollNumber;
    @ToString.Exclude
    private String idNumber;
    private BigDecimal monthlyInstallment;
    private long tenor;

    @ToString.Include(name = "ecnumber", rank = 1)
    private String maskedEcnumber() {
        return NdasendaLoanApprovalServiceImpl.maskEcNumber(ecnumber);
    }
}
