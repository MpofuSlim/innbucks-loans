package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffLoanVoucherIssuanceRepository extends JpaRepository<StaffLoanVoucherIssuance, Long> {

    /** A loan's issuance: one per loan. */
    Optional<StaffLoanVoucherIssuance> findByStaffLoanId(Long staffLoanId);

    List<StaffLoanVoucherIssuance> findByStaffLoanIdIn(Collection<Long> staffLoanIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StaffLoanVoucherIssuance i where i.staffLoanId = :staffLoanId")
    Optional<StaffLoanVoucherIssuance> lockByStaffLoanId(@Param("staffLoanId") Long staffLoanId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StaffLoanVoucherIssuance i where i.id = :id")
    Optional<StaffLoanVoucherIssuance> lockById(@Param("id") Long id);

    /** Whether a prefix is taken in a store: a new issuance's nonce is drawn again if so. */
    boolean existsByStoreModeAndCouponPrefix(GetMoreMode storeMode, String couponPrefix);

    /** Issuances in {@code statuses}, newest first: what staff have to look at. */
    Page<StaffLoanVoucherIssuance> findByStatusInOrderByIdDesc(Collection<StaffLoanVoucherIssuanceStatus> statuses,
                                                              Pageable pageable);

    long countByStatus(StaffLoanVoucherIssuanceStatus status);

    /**
     * The issuances of a staff member's loans in {@code statuses}, newest first, at most {@code limit}: what a
     * borrower's read moves on.
     */
    default List<StaffLoanVoucherIssuance> findForMember(Long staffMemberId,
                                                         Collection<StaffLoanVoucherIssuanceStatus> statuses,
                                                         int limit) {
        return forMember(staffMemberId, statuses, PageRequest.of(0, limit));
    }

    @Query("select i from StaffLoanVoucherIssuance i where i.status in :statuses and i.staffLoanId in"
            + " (select l.id from StaffLoan l where l.staffMemberId = :member) order by i.id desc")
    List<StaffLoanVoucherIssuance> forMember(@Param("member") Long staffMemberId,
                                             @Param("statuses") Collection<StaffLoanVoucherIssuanceStatus> statuses,
                                             Pageable pageable);
}
