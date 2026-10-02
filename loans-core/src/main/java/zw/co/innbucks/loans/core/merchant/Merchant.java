package zw.co.innbucks.loans.core.merchant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.loan.BaseEntity;
import zw.co.innbucks.loans.core.loan.DisbursementType;

import jakarta.persistence.*;

@Data
@Table(name = "merchants", indexes = {
        @Index(name = "idx_merchants_merchant_code", columnList = "merchant_code")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Merchant extends BaseEntity {

    public static final String DEFAULT_MERCHANT_CODE = "innbucks-2562-4f1f-b961-c546ea7c0481";

    public static final String DEFAULT_MERCHANT_NAME = "Innbucks";

    private String merchantCode;

    @Column(name = "name")
    private String companyName;

    private String accountNumber;

    @Enumerated(EnumType.STRING)
    private DisbursementType disbursementType;

    @ManyToOne
    private CommissionGroup commissionGroup;

    @Enumerated(EnumType.STRING)
    private CommissionStructure commissionStructure;

    @Column(name = "physical_address")
    private String physicalAddress;

    @Column(name = "contact_person_name")
    private String contactPersonName;

    @Column(name = "contact_person_mobile_number")
    private String contactPersonMobileNumber;

    @Column(name = "contact_person_email")
    private String contactPersonEmail;

    /**
     * Whether this is the Staff Grocery Loan's merchant: the one a new loan is accepted for, paid to, and whose tills
     * its voucher is spent at. At most one merchant is, and only {@link StaffLoanMerchantService} changes which.
     */
    @Column(name = "staff_loan_merchant", nullable = false)
    private boolean staffLoanMerchant;

}
