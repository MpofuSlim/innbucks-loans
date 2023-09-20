package zw.co.reikan.nanoloansweb.loan;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class LoanRequest implements Serializable {
    private String signatureData;
    private BigDecimal amount;
    private String ecnumber;
    private String mobileNumber;

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
