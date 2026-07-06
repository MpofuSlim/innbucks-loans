package zw.co.reikan.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.reikan.loans.core.api.LoanStatisticsResponse;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanRepository extends JpaRepository<Loan, Long>, JpaSpecificationExecutor<Loan> {

    Optional<Loan> findByEcNumberAndLoanApprovalStatus(String ecNumber, LoanApprovalStatus loanApprovaStatus);

    Optional<Loan> findTopByNationalIdNumberAndLoanApprovalStatusIn(String idNumber, List<LoanApprovalStatus> statuses);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    List<Loan> findByLoanAccountStatusAndDisbursementStatusAndInternalApprovalStatus(LoanAccountStatus loanAccountStatus,
                                                                                     LoanDisbursementStatus disbursementStatus,
                                                                                     InternalApprovalStatus internalApprovalStatus);

    List<Loan> findByLoanAccountStatusAndDisbursementStatus(LoanAccountStatus loanAccountStatus,
                                                           LoanDisbursementStatus disbursementStatus);

    List<Loan> findByLoanApprovalStatusAndInternalApprovalStatusAndLoanAccountStatus(LoanApprovalStatus loanApprovaStatus,
                                                                                     InternalApprovalStatus internalApprovalStatus,
                                                                                     LoanAccountStatus loanAccountStatus);

    List<Loan> findByCreatedDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    @Query("""
            select new zw.co.reikan.loans.core.api.LoanStatisticsResponse(sum(l.principal), sum(l.agentCommission), count(l)) from Loan l 
            where l.createdByUser.id = :agentId and l.dateDisbursed between :startDate and :endDate
            and l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    LoanStatisticsResponse getLoanStatistics(@Param("agentId") Long agentId,
                                             @Param("startDate") LocalDateTime startDate,
                                             @Param("endDate") LocalDateTime endDate);

    // --- Admin dashboard aggregates -----------------------------------------

    long countByLoanApprovalStatusAndInternalApprovalStatus(LoanApprovalStatus loanApprovalStatus,
                                                            InternalApprovalStatus internalApprovalStatus);

    @Query("select l.loanApprovalStatus, count(l) from Loan l group by l.loanApprovalStatus")
    List<Object[]> countGroupedByApprovalStatus();

    @Query("select l.disbursementStatus, count(l) from Loan l where l.disbursementStatus is not null group by l.disbursementStatus")
    List<Object[]> countGroupedByDisbursementStatus();

    @Query("""
            select coalesce(sum(l.disbursedAmount), 0) from Loan l
            where l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    BigDecimal sumDisbursedAmountForSuccessfulDisbursements();

    @Query("""
            select coalesce(sum(l.agentCommission), 0) from Loan l
            where l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    BigDecimal sumAgentCommissionForSuccessfulDisbursements();
}
