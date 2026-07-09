package zw.co.reikan.loans.core.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Loads a loan under a row-level write lock (SELECT ... FOR UPDATE). Used by
     * the disbursement flow to serialise concurrent disburse calls for the same
     * loan across all app nodes: a second caller blocks here until the first
     * commits, then sees the SUCCESS status and skips a duplicate payout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findByIdForUpdate(@Param("id") Long id);

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

    // --- Reporting aggregates ------------------------------------------------

    /**
     * Daily disbursement totals (successful disbursements only). Native for the day
     * grouping; merchant joined LEFT so a null filter keeps loans without a merchant.
     * The merchant table's code column is physically camelCase, hence the quoting.
     */
    @Query(value = """
            select cast(l.date_disbursed as date) as day,
                   count(*) as loan_count,
                   coalesce(sum(l.disburse_amount), 0) as total_disbursed
            from loan_request l
            left join merchant m on m.id = l.merchant_id
            where l.disbursement_status = 'SUCCESS'
              and l.date_disbursed between :startDate and :endDate
              and (cast(:merchantCode as text) is null or m."merchantCode" = cast(:merchantCode as text))
            group by day
            order by day
            """, nativeQuery = true)
    List<Object[]> disbursementsByDay(@Param("startDate") LocalDateTime startDate,
                                      @Param("endDate") LocalDateTime endDate,
                                      @Param("merchantCode") String merchantCode);

    @Query("""
            select l.loanApprovalStatus, count(l), coalesce(sum(l.principal), 0)
            from Loan l left join l.merchant m
            where l.createdDate between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by l.loanApprovalStatus
            """)
    List<Object[]> portfolioByApprovalStatus(@Param("startDate") LocalDateTime startDate,
                                             @Param("endDate") LocalDateTime endDate,
                                             @Param("merchantCode") String merchantCode);

    @Query("""
            select m.merchantCode, m.companyName, count(l),
                   coalesce(sum(l.agentCommission), 0), coalesce(sum(l.providerCommission), 0)
            from Loan l join l.merchant m
            where l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
              and l.dateDisbursed between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by m.merchantCode, m.companyName
            order by m.companyName
            """)
    List<Object[]> commissionsByMerchant(@Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("merchantCode") String merchantCode);

    @Query("""
            select m.merchantCode, m.companyName, count(l), coalesce(sum(l.principal), 0),
                   count(case when l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS then 1 end),
                   coalesce(sum(case when l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS then l.disbursedAmount end), 0)
            from Loan l join l.merchant m
            where l.createdDate between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by m.merchantCode, m.companyName
            order by m.companyName
            """)
    List<Object[]> merchantPerformance(@Param("startDate") LocalDateTime startDate,
                                       @Param("endDate") LocalDateTime endDate,
                                       @Param("merchantCode") String merchantCode);

    @Query("""
            select u.id, u.username, count(l),
                   coalesce(sum(l.disbursedAmount), 0), coalesce(sum(l.agentCommission), 0)
            from Loan l join l.createdByUser u left join l.merchant m
            where l.disbursementStatus = zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
              and l.dateDisbursed between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by u.id, u.username
            order by u.username
            """)
    List<Object[]> agentPerformance(@Param("startDate") LocalDateTime startDate,
                                    @Param("endDate") LocalDateTime endDate,
                                    @Param("merchantCode") String merchantCode);
}
