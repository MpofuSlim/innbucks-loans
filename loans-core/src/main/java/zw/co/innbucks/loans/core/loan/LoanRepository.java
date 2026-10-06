package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.dashboard.DashboardLoanGroup;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanRepository extends JpaRepository<Loan, Long>, JpaSpecificationExecutor<Loan> {

    /**
     * The loan list (GET /loans and its credit-queue twin): each row renders its merchant, originator and channel, so
     * they come in the page's own query (to-one joins, safe under a page limit) rather than one batch each after it.
     * The count query is not affected. LOAD graph: what is not named keeps its mapping.
     */
    @Override
    @EntityGraph(attributePaths = {"merchant", "createdByUser", "channel"}, type = EntityGraph.EntityGraphType.LOAD)
    Page<Loan> findAll(Specification<Loan> spec, Pageable pageable);

    /** One loan in full (GET /loans/{loanId}), with what its view renders. */
    @Override
    @EntityGraph(attributePaths = {"merchant", "createdByUser", "channel"}, type = EntityGraph.EntityGraphType.LOAD)
    Optional<Loan> findOne(Specification<Loan> spec);

    /** One loan with its merchant, originator and channel: the credit workbench, which renders them. */
    @EntityGraph(attributePaths = {"merchant", "createdByUser", "channel"}, type = EntityGraph.EntityGraphType.LOAD)
    Optional<Loan> findWithAssociationsById(Long id);

    /**
     * These loans with their merchant, in id order: for a job that reads a chunk of loans and then works on them with
     * no transaction open (LoanDisbursementStatusJob), where the payout SMS names the merchant. No lock.
     */
    @Query("select l from Loan l left join fetch l.merchant where l.id in :ids order by l.id")
    List<Loan> findWithMerchantByIdIn(@Param("ids") Collection<Long> ids);

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
     * review (FR-SSB-007) or for an employment event (FR-SSB-024), and not already declined. Oldest first, one
     * chunk at a time: the ids above {@code after} (see {@code IdChunks}).
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
              and l.id > :after
            order by l.id
            """)
    List<Long> findIdsDueForLodgement(@Param("now") LocalDateTime now, @Param("after") long after, Pageable chunk);

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

    /** Loans waiting on Credit: SSB has accepted the deduction and Credit has not decided. */
    @Query("""
            select l from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.innbucks.loans.core.loan.InternalApprovalStatus.PENDING
            order by l.id
            """)
    List<Loan> findAwaitingCreditDecision();

    List<Loan> findByInternalApprovalStatusOrderByIdAsc(InternalApprovalStatus internalApprovalStatus);

    /**
     * Loans at the point before lodgement (FR-SSB-014 checkpoints): NEW, not declined, and not being lodged right now.
     * Mirrors {@code CheckpointQueue.atHoldPoint}.
     */
    @Query("""
            select l from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.NEW
              and (l.internalApprovalStatus is null
                   or l.internalApprovalStatus <> zw.co.innbucks.loans.core.loan.InternalApprovalStatus.REJECTED)
              and l.lodgementClaimedAt is null
            order by l.id
            """)
    List<Loan> findBeforeLodgement();

    /**
     * Loans at the point before Credit approves (FR-SSB-014 checkpoints): accepted by SSB, with Credit yet to approve
     * or reject them. Mirrors {@code CheckpointQueue.atHoldPoint}.
     */
    @Query("""
            select l from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus in (zw.co.innbucks.loans.core.loan.InternalApprovalStatus.PENDING,
                                               zw.co.innbucks.loans.core.loan.InternalApprovalStatus.RETURNED)
            order by l.id
            """)
    List<Loan> findBeforeCreditApproval();

    /**
     * Loans at the point before booking (FR-SSB-014 checkpoints): credit-approved and waiting to be booked, not being
     * booked right now, never paid, and with no booking of unknown outcome. Mirrors
     * {@code CheckpointQueue.atHoldPoint}.
     */
    @Query("""
            select l from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.APPROVED
              and l.internalApprovalStatus = zw.co.innbucks.loans.core.loan.InternalApprovalStatus.APPROVED
              and l.loanAccountStatus = zw.co.innbucks.loans.core.disbursements.LoanAccountStatus.PENDING
              and l.bookingClaimedAt is null
              and (l.disbursementStatus is null
                   or l.disbursementStatus <> zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus.SUCCESS)
              and (l.bookingFailureKind is null
                   or l.bookingFailureKind <> zw.co.innbucks.loans.core.disbursements.BookingFailureKind.AMBIGUOUS)
            order by l.id
            """)
    List<Loan> findBeforeBooking();

    /** Payslip reviews decided in the period. */
    List<Loan> findByPayslipReviewedAtBetween(LocalDateTime from, LocalDateTime to);

    /** Deduction cancellations recorded as done in the period. */
    List<Loan> findByDeductionCancelledAtBetween(LocalDateTime from, LocalDateTime to);

    /** Each loan's id and SSB approval time. */
    @Query("select l.id, l.dateApproved from Loan l where l.id in :ids")
    List<Object[]> findDateApprovedByIdIn(@Param("ids") Collection<Long> ids);

    /** Every loan under an EC number, as stored (upper case), oldest first. */
    List<Loan> findByEcNumberOrderByIdAsc(String ecNumber);

    /** Every loan under a national ID, as stored, oldest first. */
    List<Loan> findByNationalIdNumberOrderByIdAsc(String nationalIdNumber);

    /** The applications waiting in the payslip review queue, oldest first. */
    List<Loan> findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus payslipReviewStatus);

    /** NEW loans whose lodgement was claimed before {@code cutoff} and never settled; a chunk above {@code after}. */
    @Query("""
            select l.id from Loan l
            where l.loanApprovalStatus = zw.co.innbucks.loans.core.loan.LoanApprovalStatus.NEW
              and l.lodgementClaimedAt < :cutoff
              and l.id > :after
            order by l.id
            """)
    List<Long> findIdsWithLodgementClaimedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("after") long after,
                                                 Pageable chunk);

    /**
     * Credit-approved loans due for booking with InnBucks: account PENDING, unclaimed, and not held for an
     * employment event (FR-SSB-024). Oldest first, one chunk at a time: the ids above {@code after}.
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
              and l.id > :after
            order by l.id
            """)
    List<Long> findIdsDueForBooking(@Param("after") long after, Pageable chunk);

    /** Account-PENDING loans whose booking was claimed before {@code cutoff} and never settled; a chunk above {@code after}. */
    @Query("""
            select l.id from Loan l
            where l.loanAccountStatus = zw.co.innbucks.loans.core.disbursements.LoanAccountStatus.PENDING
              and l.bookingClaimedAt < :cutoff
              and l.id > :after
            order by l.id
            """)
    List<Long> findIdsWithBookingClaimedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("after") long after,
                                               Pageable chunk);

    List<Loan> findByLoanAccountStatusAndDisbursementStatusAndInternalApprovalStatus(LoanAccountStatus loanAccountStatus,
                                                                                     LoanDisbursementStatus disbursementStatus,
                                                                                     InternalApprovalStatus internalApprovalStatus);

    List<Loan> findByLoanAccountStatusAndDisbursementStatus(LoanAccountStatus loanAccountStatus,
                                                           LoanDisbursementStatus disbursementStatus);

    /** The ids of {@link #findByLoanAccountStatusAndDisbursementStatus}, a chunk above {@code after} in id order. */
    @Query("""
            select l.id from Loan l
            where l.loanAccountStatus = :accountStatus and l.disbursementStatus = :disbursementStatus
              and l.id > :after
            order by l.id
            """)
    List<Long> findIdsByLoanAccountStatusAndDisbursementStatus(@Param("accountStatus") LoanAccountStatus accountStatus,
                                                               @Param("disbursementStatus") LoanDisbursementStatus disbursementStatus,
                                                               @Param("after") long after, Pageable chunk);

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

    /**
     * Every loan figure on the admin dashboard, from ONE scan of loans: a row per combination of SSB approval,
     * Credit decision and disbursement status (a few dozen at most), with its count and its two sums.
     * {@code DashboardServiceImpl} folds the totals, both status maps, the loans awaiting Credit and the SUCCESS sums
     * out of them. It replaced five statements that each read the whole table.
     */
    @Query("""
            select new zw.co.innbucks.loans.core.dashboard.DashboardLoanGroup(
                       l.loanApprovalStatus, l.internalApprovalStatus, l.disbursementStatus,
                       count(l), sum(l.disbursedAmount), sum(l.agentCommission))
            from Loan l
            group by l.loanApprovalStatus, l.internalApprovalStatus, l.disbursementStatus
            """)
    List<DashboardLoanGroup> dashboardGroups();

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
