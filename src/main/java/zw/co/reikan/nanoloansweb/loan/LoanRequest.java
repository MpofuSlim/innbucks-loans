package zw.co.reikan.nanoloansweb.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoanRequest implements Serializable {
    private String signatureData;
    private BigDecimal amount;
    private String ecnumber;
    private String mobileNumber;
    private LocalDate startDate;
    private int tenor;
    private BigDecimal interestRate;
    private BigDecimal adminFeeRate;
    private BigDecimal commissionRate;
    private LoanAmountType type;


    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("LoanRequest{");
        sb.append("signatureData='").append(signatureData).append('\'');
        sb.append(", amount=").append(amount);
        sb.append(", ecnumber='").append(ecnumber).append('\'');
        sb.append(", mobileNumber='").append(mobileNumber).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
