package zw.co.innbucks.loans.core.loan;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Loan entity to API views. The entity keeps its storage names; the views use the names the API
 * publishes, one per lending stage (ssb*, credit*, booking*, disbursement*).
 */
@Mapper(componentModel = "spring")
public interface LoanMapper {

    @Mapping(target = "createdAt", source = "createdDate")
    @Mapping(target = "merchantCode", source = "merchant.merchantCode")
    @Mapping(target = "merchantName", source = "merchant.companyName")
    @Mapping(target = "numberOfDependants", source = "numberOfDependencies")
    @Mapping(target = "commissionPercentage", source = "commissionRatePercentage")
    @Mapping(target = "startDate", source = "loanStartDate")
    @Mapping(target = "endDate", source = "loanEndDate")
    @Mapping(target = "ssbApprovalStatus", source = "loanApprovalStatus")
    @Mapping(target = "ssbStatusMessage", source = "loanStatusMessage")
    @Mapping(target = "ssbStatusChangedAt", source = "dateApproved")
    @Mapping(target = "ssbDeductionId", source = "approvalReference")
    @Mapping(target = "creditApprovalStatus", source = "internalApprovalStatus")
    @Mapping(target = "creditDecisionAt", source = "internalApprovalDate")
    @Mapping(target = "creditDecisionBy", source = "internalApprovalBy")
    @Mapping(target = "creditDecisionComment", source = "internalApprovalComment")
    @Mapping(target = "creditDecisionReasonCode", source = "internalApprovalReasonCode")
    @Mapping(target = "documents", ignore = true)
    @Mapping(target = "bookingStatus", source = "loanAccountStatus")
    @Mapping(target = "disbursedAt", source = "dateDisbursed")
    LoanResponse toResponse(Loan loan);

    @Mapping(target = "createdAt", source = "createdDate")
    @Mapping(target = "merchantCode", source = "merchant.merchantCode")
    @Mapping(target = "merchantName", source = "merchant.companyName")
    @Mapping(target = "ssbApprovalStatus", source = "loanApprovalStatus")
    @Mapping(target = "creditApprovalStatus", source = "internalApprovalStatus")
    @Mapping(target = "bookingStatus", source = "loanAccountStatus")
    LoanSummaryResponse toSummary(Loan loan);
}
