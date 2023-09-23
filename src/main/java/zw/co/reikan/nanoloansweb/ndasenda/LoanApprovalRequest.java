package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class LoanApprovalRequest implements Serializable {
    private BigDecimal totalAmount;
    private String ecnumber;
    private String reference;
    private String name;
    private String surname;
    private String payrollNumber;
    private String idNumber;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal monthlyInstallment;
}
