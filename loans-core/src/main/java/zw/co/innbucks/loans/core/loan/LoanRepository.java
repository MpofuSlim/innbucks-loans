package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
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
            select new zw.co.innbucks.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
                   l.internalApprovalStatus, l.loanAccountStatus, l.disbursementStatus)
            from Loan l where upper(l.ecNumber) = :ecNumber
            """)
    List<LoanStatusSnapshot> findStatusesByEcNumber(@Param("ecNumber") String ecNumber);

    /** As {@link #findStatusesByEcNumber}, by national ID. */
    @Query("""
            select new zw.co.innbucks.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
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
            select new zw.co.innbucks.loans.core.loan.NdasendaAwaitingLoan(l.id, l.loanApprovalStatus,
                   l.batchNumber, l.ecNumber, l.dateApproved, l.createdDate, l.ndasendaResponseOverdueAt)
            from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.PROCESSING
               or (l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.FAILED
                   and ((l.batchNumber is not null and trim(l.batchNumber) <> '')
                        or (l.approvalReference is not null and trim(l.approvalReference) <> ''))
                   and (l.deductionCancellationStatus is null
                        or l.deductionCancellationStatus
                           <> zw.co.innbucks.loans.core.loan.DeductionCancellationStatus.CANCELLED_EXTERNALLY))
            """)
    List<NdasendaAwaitingLoan> findAwaitingNdasendaOutcome();

    Optional<Loan> findTopByNationalIdNumberAndLoanApprovalStatusIn(String idNumber, List<LoanApprovalStatus> statuses);

    List<Loan> findByLoanApprovalStatus(LoanApprovalStatus loanApprovaStatus);

    /**
     * NEW loans due for lodgement with Ndasenda: unclaimed, past any retry backoff, not held for payslip
     * review (FR-SSB-007) or for an employment event (FR-SSB-024), and not already declined. Oldest first.
     */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.NEW
              and l.lodgementClaimedAt is null
              and (l.nextLodgementAttemptAt is null or l.nextLodgementAttemptAt <= :now)
              and (l.payslipReviewStatus is null
                   or l.payslipReviewStatus = zw.co.innbucks.loans.core.loan.PayslipReviewStatus.CLEARED)
              and (l.internalApprovalStatus is null
                   or l.internalApprovalStatus <> zw.co.innbucks.loans.core.loan.InternalApprovalStatus.REJECTED)
              and not exists (select h.id from LoanEmploymentEvent h where h.loanId = l.id
                   and h.action = zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction.HOLD
                   and h.status = zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus.OPEN)
            order by l.id
            """)
    List<Long> findIdsDueForLodgement(@Param("now") LocalDateTime now);

    /**
     * Whether an employment event holds the loan (FR-SSB-024): it is then not lodged, credit-approved or booked
     * until an officer releases or declines it. Read under the loan's row lock by the jobs that claim it.
     */
    @Query("""
            select count(h) > 0 from LoanEmploymentEvent h
            where h.loanId = :loanId
              and h.action = zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction.HOLD
              and h.status = zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus.OPEN
            """)
    boolean isHeldForEmploymentEvent(@Param("loanId") Long loanId);

    /**
     * Loans waiting on Credit whose current wait reached Credit before {@code cutoff} and has not been escalated
     * (FR-PBL-030). Oldest first.
     */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.innbucks.loans.core.loan.InternalApprovalStatus.PENDING
              and l.creditEscalatedAt is null
              and coalesce(l.creditResubmittedAt, l.dateApproved) <= :cutoff
            order by l.id
            """)
    List<Long> findIdsDueForCreditEscalation(@Param("cutoff") LocalDateTime cutoff);

    /**
     * How many loans wait on Credit now; of those, how many reached it before {@code cutoff} (past the target, when the
     * cutoff is now less the target), and how many are escalated.
     */
    @Query("""
            select count(l),
                   coalesce(sum(case when coalesce(l.creditResubmittedAt, l.dateApproved) < :cutoff then 1 else 0 end), 0),
                   coalesce(sum(case when l.creditEscalatedAt is not null then 1 else 0 end), 0)
            from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.innbucks.loans.core.loan.InternalApprovalStatus.PENDING
            """)
    List<Object[]> countAwaitingCredit(@Param("cutoff") LocalDateTime cutoff);

    /** Each loan's id and SSB approval time. */
    @Query("select l.id, l.dateApproved from Loan l where l.id in :ids")
    List<Object[]> findDateApprovedByIdIn(@Param("ids") Collection<Long> ids);

    /** Every loan under an EC number, as stored (upper case), oldest first. */
    List<Loan> findByEcNumberOrderByIdAsc(String ecNumber);

    /** Every loan under a national ID, as stored, oldest first. */
    List<Loan> findByNationalIdNumberOrderByIdAsc(String nationalIdNumber);

    /** The applications waiting in the payslip review queue, oldest first. */
    List<Loan> findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus payslipReviewStatus);

    /** NEW loans whose lodgement was claimed before {@code cutoff} and never settled. */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.NEW
              and l.lodgementClaimedAt < :cutoff
            order by l.id
            """)
    List<Long> findIdsWithLodgementClaimedBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Credit-approved loans due for booking with InnBucks: account PENDING, unclaimed, and not held for an
     * employment event (FR-SSB-024). Oldest first.
     */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.innbucks.loans.core.loan.InternalApprovalStatus.APPROVED
              and l.loanAccountStatus = zw.co.innbucks.loans.core.disbursements.LoanAccountStatus.PENDING
              and l.bookingClaimedAt is null
              and not exists (select h.id from LoanEmploymentEvent h where h.loanId = l.id
                   and h.action = zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction.HOLD
                   and h.status = zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus.OPEN)
            order by l.id
            """)
    List<Long> findIdsDueForBooking();

    /** Account-PENDING loans whose booking was claimed before {@code cutoff} and never settled. */
    @Query("""
            select l.id from Loan l
            where l.loanAccountStatus = zw.co.innbucks.loans.core.disbursements.LoanAccountStatus.PENDING
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
            select new zw.co.innbucks.loans.core.loan.SalesSummaryResponse(sum(l.principal), sum(l.agentCommission), count(l))
            from Loan l
            where l.createdByUser.id = :userId and l.dateDisbursed between :startDate and :endDate
            and l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    SalesSummaryResponse salesSummary(@Param("userId") Long userId,
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
            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    BigDecimal sumDisbursedAmountForSuccessfulDisbursements();

    @Query("""
            select coalesce(sum(l.agentCommission), 0) from Loan l
            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
            """)
    BigDecimal sumAgentCommissionForSuccessfulDisbursements();

    // --- Reporting aggregates ------------------------------------------------

    /**
     * Daily disbursement totals (successful disbursements only). Native for the day
     * grouping; merchant joined LEFT so a null filter keeps loans without a merchant.
     * The day is the market's ({@code zone}): the column holds UTC, and grouping on its
     * UTC date put every loan paid between midnight and 02:00 in Harare on the day before.
     */
    @Query(value = """
            select cast((l.date_disbursed at time zone 'UTC') at time zone cast(:zone as text) as date) as day,
                   count(*) as loan_count,
                   coalesce(sum(l.disbursed_amount), 0) as total_disbursed
            from loans l
            left join merchants m on m.id = l.merchant_id
            where l.disbursement_status = 'SUCCESS'
              and l.date_disbursed between :startDate and :endDate
              and (cast(:merchantCode as text) is null or m.merchant_code = cast(:merchantCode as text))
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
            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
              and l.dateDisbursed between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by m.merchantCode, m.companyName
            order by m.companyName
            """)
    List<Object[]> commissionsByMerchant(@Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("merchantCode") String merchantCode);

    @Query("""
            select u.id, u.username, u.firstName, u.lastName, count(l),
                   coalesce(sum(l.disbursedAmount), 0), coalesce(sum(l.agentCommission), 0)
            from Loan l join l.createdByUser u left join l.merchant m
            where l.disbursementStatus = zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS
              and l.dateDisbursed between :startDate and :endDate
              and (cast(:merchantCode as string) is null or m.merchantCode = :merchantCode)
            group by u.id, u.username, u.firstName, u.lastName
            order by u.username
            """)
    List<Object[]> agentPerformance(@Param("startDate") LocalDateTime startDate,
                                    @Param("endDate") LocalDateTime endDate,
                                    @Param("merchantCode") String merchantCode);
}
