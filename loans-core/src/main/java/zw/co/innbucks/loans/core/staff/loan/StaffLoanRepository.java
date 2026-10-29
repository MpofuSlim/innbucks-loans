package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffLoanRepository extends JpaRepository<StaffLoan, Long>, JpaSpecificationExecutor<StaffLoan> {

    /** The loans that bear on whether these members may borrow: open ones, and written-off ones. */
    List<StaffLoan> findByStaffMemberIdInAndStatusIn(Collection<Long> staffMemberIds,
                                                    Collection<StaffLoanStatus> statuses);

    /** Whether the member has a loan in {@code status}: a written-off one, say. */
    boolean existsByStaffMemberIdAndStatus(Long staffMemberId, StaffLoanStatus status);

    /**
     * Paid-out loans not recovered in full on {@code today} (FR-SGL-045): written off; or still DISBURSED and past their
     * due date, or with a borrower no longer ACTIVE. With their merchant.
     */
    @Query("select l from StaffLoan l join fetch l.merchant where l.status = :writtenOff or (l.status = :disbursed"
            + " and (l.dueDate < :today or l.employmentFlag is not null))")
    List<StaffLoan> findNotRecovered(@Param("today") LocalDate today,
                                     @Param("disbursed") StaffLoanStatus disbursed,
                                     @Param("writtenOff") StaffLoanStatus writtenOff);

    /** One member's loans, whatever became of them. */
    Page<StaffLoan> findByStaffMemberId(Long staffMemberId, Pageable pageable);

    Optional<StaffLoan> findFirstByStaffMemberIdAndStatusInOrderByIdDesc(Long staffMemberId,
                                                                         Collection<StaffLoanStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StaffLoan l where l.id = :id")
    Optional<StaffLoan> lockById(@Param("id") Long id);

    @Query(value = "SELECT nextval('staff_loan_reference_seq')", nativeQuery = true)
    long nextReferenceNumber();

    /**
     * The ids of one member's loans in {@code statuses}, newest first, without loading them: a caller that acts on one
     * locks it with {@link #lockById}, so the entity it decides on is first read under the lock.
     */
    @Query("select l.id from StaffLoan l where l.staffMemberId = :staffMemberId and l.status in :statuses"
            + " order by l.id desc")
    List<Long> findIdsByStaffMemberIdAndStatusIn(@Param("staffMemberId") Long staffMemberId,
                                                 @Param("statuses") Collection<StaffLoanStatus> statuses);

    // The cash payout queues. Each repeats the literals of idx_staff_loans_payout_pending (or _failed), so the planner
    // can prove it against the index predicate; each is a keyset chunk for IdChunks: the ids above {@code after}.

    /** Cash payouts READY to send, oldest first: a chunk above {@code after}. */
    @Query("""
            select l.id from StaffLoan l
            where l.payoutMethod = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod.CASH
              and l.status = zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus.AWAITING_DISBURSEMENT
              and l.payoutStatus = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus.READY
              and l.id > :after
            order by l.id
            """)
    List<Long> findPayoutReadyIds(@Param("after") long after, Pageable chunk);

    /** Cash payouts the bank's inquiry settles, BOOKED or IN_DOUBT, oldest first: a chunk above {@code after}. */
    @Query("""
            select l.id from StaffLoan l
            where l.payoutMethod = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod.CASH
              and l.status = zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus.AWAITING_DISBURSEMENT
              and l.payoutStatus in (zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus.BOOKED,
                                     zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus.IN_DOUBT)
              and l.id > :after
            order by l.id
            """)
    List<Long> findPayoutAwaitingInquiryIds(@Param("after") long after, Pageable chunk);

    /**
     * Cash payouts claimed for sending before {@code cutoff} and never settled (the process died mid-call): a chunk
     * above {@code after}.
     */
    @Query("""
            select l.id from StaffLoan l
            where l.payoutMethod = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod.CASH
              and l.status = zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus.AWAITING_DISBURSEMENT
              and l.payoutStatus = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus.SENDING
              and l.payoutClaimedAt < :cutoff
              and l.id > :after
            order by l.id
            """)
    List<Long> findPayoutClaimedBeforeIds(@Param("cutoff") LocalDateTime cutoff, @Param("after") long after,
                                          Pageable chunk);

    /**
     * Cash payouts a person confirmed were never booked at or after {@code since}, still watched in case the bank
     * booked them after all; one the bank has since been found to hold is not asked about again. A chunk above
     * {@code after}.
     */
    @Query("""
            select l.id from StaffLoan l
            where l.payoutStatus = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus.FAILED
              and l.payoutPaidAfterCancelAt is null
              and l.payoutFailedAt >= :since
              and l.id > :after
            order by l.id
            """)
    List<Long> findPayoutFailedSinceIds(@Param("since") LocalDateTime since, @Param("after") long after,
                                        Pageable chunk);

    /** Cash payouts awaiting disbursement, how many and how old, by payout status: what the payout gauges show. */
    @Query("""
            select new zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutBacklog(
                l.payoutStatus, count(l), min(l.acceptedAt), min(l.payoutClaimedAt))
            from StaffLoan l
            where l.payoutMethod = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod.CASH
              and l.status = zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus.AWAITING_DISBURSEMENT
            group by l.payoutStatus
            """)
    List<StaffLoanPayoutBacklog> payoutBacklog();

    /**
     * Records that the bank took a cash payout (a 2xx to its booking) whose settle could not be saved: seen by the bank
     * for good, with its answer to the booking. Nothing else moves: the stale-claim hold then makes it BOOKED, and no
     * person can record it as never booked. Matches nothing once the bank has been seen.
     *
     * @return 1 when it was recorded, 0 when there was nothing to record
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update StaffLoan l
            set l.payoutBankSeenAt = :at, l.payoutApplyAnswer = :answer, l.version = l.version + 1
            where l.id = :id
              and l.payoutMethod = zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod.CASH
              and l.payoutBankSeenAt is null
            """)
    int markPayoutSeenByBank(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("answer") String answer);

    /** Whether the bank was found to hold a payout of this member's after a person had confirmed it was never booked. */
    boolean existsByStaffMemberIdAndPayoutPaidAfterCancelAtIsNotNull(Long staffMemberId);
}
