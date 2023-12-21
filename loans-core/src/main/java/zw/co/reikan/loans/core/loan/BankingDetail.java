package zw.co.reikan.loans.core.loan;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;

@Data
@Embeddable
public class BankingDetail {

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_name")
    private String accountName;

    @Column(name = "bank_branch_name")
    private String branchName;

    @Column(name = "bank_branch_code")
    private String branchCode;

    @Column(name = "bank_account_number")
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_account_type")
    private BankAccountType accountType;
}