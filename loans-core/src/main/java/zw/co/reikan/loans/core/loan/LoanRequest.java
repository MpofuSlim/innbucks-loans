package zw.co.reikan.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoanRequest implements Serializable {
    private String signatureData;
    private BigDecimal amount;
    private String ecnumber;
    private String mobileNumber;
    private int tenor;
    private String nationalId;
    private LoanAmountType type = LoanAmountType.NET_OF_FEES;

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("LoanRequest{");
        sb.append("signatureData='").append(signatureData).append('\'');
        sb.append(", amount=").append(amount);
        sb.append(", ecnumber='").append(ecnumber).append('\'');
        sb.append(", mobileNumber='").append(mobileNumber).append('\'');
        sb.append(", tenor=").append(tenor);
        sb.append(", nationalId='").append(nationalId).append('\'');
        sb.append(", type=").append(type);
        sb.append('}');
        return sb.toString();
    }
}
