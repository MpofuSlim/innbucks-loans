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

    /**
     * Every loan on file under this EC number, as its status columns only. The
     * column is compared upper-cased because rows stored before the EC number
     * was normalised can carry a lower-case check letter. Which of them count as
     * a pending application is {@link LoanStatusSnapshot#isInFlight()}'s call.
     */
    @Query("""
            select new zw.co.reikan.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
                   l.internalApprovalStatus, l.loanAccountStatus, l.disbursementStatus)
            from Loan l where upper(l.ecNumber) = :ecNumber
            """)
    List<LoanStatusSnapshot> findStatusesByEcNumber(@Param("ecNumber") String ecNumber);

    /** As {@link #findStatusesByEcNumber}, by national ID. */
    @Query("""
            select new zw.co.reikan.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
                   l.internalApprovalStatus, l.loanAccountStatus, l.disbursementStatus)
            from Loan l where upper(l.nationalIdNumber) = :nationalIdNumber
            """)
    List<LoanStatusSnapshot> findStatusesByNationalId(@Param("nationalIdNumber") String nationalIdNumber);

    /**
     * Takes a transaction-scoped Postgres advisory lock on {@code key}, waiting
     * while another transaction holds it. It is released when the CALLER'S
     * transaction ends, so it only guards work inside that same transaction.
     * Selected FROM the function so the result is a plain integer, not Postgres' void.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockApplicant(@Param("key") String key);

    /**
     * Loans still waiting on Ndasenda's answer to their lodgement, as the columns the response job
     * dates them by. Mirrors {@code NdasendaLoanApprovalServiceImpl.awaitingNdasendaOutcome}:
     * PROCESSING, or FAILED with a lodgement reference and no cancellation recorded at Ndasenda. The
     * blank check here only trims spaces, so it can admit a loan that predicate would not — which
     * only widens the window read; anything acted on is re-checked against the predicate itself.
     */
    @Query("""
            select new zw.co.reikan.loans.core.loan.NdasendaAwaitingLoan(l.id, l.loanApprovalStatus,
                   l.batchNumber, l.ecNumber, l.dateApproved, l.createdDate, l.ndasendaResponseOverdueAt)
            from Loan l
            where l.loanApprovalStatus = zw.co.reikan.loans.core.loan.LoanApprovalStatus.PROCESSING
               or (l.loanApprovalStatus = zw.co.reikan.loans.core.loan.LoanApprovalStatus.FAILED
                   and ((l.batchNumber is not null and trim(l.batchNumber) <> '')
                        or (l.approvalReference is not null and trim(l.approvalReference) <> ''))
                   and (l.deductionCancellationStatus is null
                        or l.deductionCancellationStatus
                           <> zw.co.reikan.loans.core.loan.DeductionCancellationStatus.CANCELLED_EXTERNALLY))
            """)
    List<NdasendaAwaitingLoan> findAwaitingNdasendaOutcome();

    Optional<Loan> findTopByNationalIdNumberAndLoanApprovalStatusIn(String idNumber, List<LoanApprovalStatus> statuses);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    /** NEW loans due for lodgement with Ndasenda: unclaimed and past any retry backoff. Oldest first. */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.reikan.loans.core.loan.LoanApprovalStatus.NEW
              and l.lodgementClaimedAt is null
              and (l.nextLodgementAttemptAt is null or l.nextLodgementAttemptAt <= :now)
            order by l.id
            """)
    List<Long> findIdsDueForLodgement(@Param("now") LocalDateTime now);

    /** NEW loans whose lodgement was claimed before {@code cutoff} and never settled. */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.reikan.loans.core.loan.LoanApprovalStatus.NEW
              and l.lodgementClaimedAt < :cutoff
            order by l.id
            """)
    List<Long> findIdsWithLodgementClaimedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Credit-approved loans due for booking with InnBucks: account PENDING and unclaimed. Oldest first. */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.reikan.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.reikan.loans.core.loan.InternalApprovalStatus.APPROVED
              and l.loanAccountStatus = zw.co.reikan.loans.core.disbursements.LoanAccountStatus.PENDING
              and l.bookingClaimedAt is null
            order by l.id
            """)
    List<Long> findIdsDueForBooking();

    /** Account-PENDING loans whose booking was claimed before {@code cutoff} and never settled. */
    @Query("""
            select l.id from Loan l
            where l.loanAccountStatus = zw.co.reikan.loans.core.disbursements.LoanAccountStatus.PENDING
              and l.bookingClaimedAt < :cutoff
            order by l.id
            """)
    List<Long> findIdsWithBookingClaimedBefore(@Param("cutoff") LocalDateTime cutoff);

    List<Loan> findByLoanAccountStatusAndDisbursementStatusAndInternalApprovalStatus(LoanAccountStatus loanAccountStatus,
                                                                                     LoanDisbursementStatus disbursementStatus,
                                                                                     InternalApprovalStatus internalApprovalStatus);

    List<Loan> findByLoanAccountStatusAndDisbursementStatus(LoanAccountStatus loanAccountStatus,
                                                           LoanDisbursementStatus disbursementStatus);

    /** Oldest first, id as the tie-break, so the operators' queue has a stable order. */
    List<Loan> findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
            DeductionCancellationStatus deductionCancellationStatus);

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
     * The day is the market's ({@code zone}): the column holds UTC, and grouping on its
     * UTC date put every loan paid between midnight and 02:00 in Harare on the day before.
     */
    @Query(value = """
            select cast((l.date_disbursed at time zone 'UTC') at time zone cast(:zone as text) as date) as day,
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
                                      @Param("merchantCode") String merchantCode,
                                      @Param("zone") String zone);

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
