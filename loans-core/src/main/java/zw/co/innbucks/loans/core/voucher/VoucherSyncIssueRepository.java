package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.util.List;

public interface VoucherSyncIssueRepository extends JpaRepository<VoucherSyncIssue, Long> {

    List<VoucherSyncIssue> findByVoucherIdOrderByIdAsc(Long voucherId);

    List<VoucherSyncIssue> findByVoucherIdAndClearedAtIsNullOrderByIdAsc(Long voucherId);

    boolean existsByVoucherIdAndBlockingTrueAndClearedAtIsNull(Long voucherId);

    /** Open issues of the vouchers of one GetMore store, oldest first: what an operator has to look at. */
    @Query(value = "select i from VoucherSyncIssue i where i.clearedAt is null and i.voucherId in"
            + " (select v.id from Voucher v where v.storeMode = :mode) order by i.firstSeenAt asc, i.id asc",
            countQuery = "select count(i) from VoucherSyncIssue i where i.clearedAt is null and i.voucherId in"
                    + " (select v.id from Voucher v where v.storeMode = :mode)")
    Page<VoucherSyncIssue> findOpenForStoreMode(@Param("mode") GetMoreMode storeMode, Pageable pageable);

    /** Every issue of the vouchers of one GetMore store, cleared or not, oldest first. */
    @Query(value = "select i from VoucherSyncIssue i where i.voucherId in"
            + " (select v.id from Voucher v where v.storeMode = :mode) order by i.firstSeenAt asc, i.id asc",
            countQuery = "select count(i) from VoucherSyncIssue i where i.voucherId in"
                    + " (select v.id from Voucher v where v.storeMode = :mode)")
    Page<VoucherSyncIssue> findAllForStoreMode(@Param("mode") GetMoreMode storeMode, Pageable pageable);

    /** Open issues of the vouchers of one GetMore store. */
    @Query("select count(i) from VoucherSyncIssue i where i.clearedAt is null and i.voucherId in"
            + " (select v.id from Voucher v where v.storeMode = :mode)")
    long countOpenForStoreMode(@Param("mode") GetMoreMode storeMode);
}
