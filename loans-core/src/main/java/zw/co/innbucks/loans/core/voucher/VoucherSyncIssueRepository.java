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

    /** Open issues, oldest first: what an operator has to look at. */
    Page<VoucherSyncIssue> findByClearedAtIsNullOrderByFirstSeenAtAscIdAsc(Pageable pageable);

    Page<VoucherSyncIssue> findAllByOrderByFirstSeenAtAscIdAsc(Pageable pageable);

    /** Open issues of the vouchers of one GetMore store. */
    @Query("select count(i) from VoucherSyncIssue i where i.clearedAt is null and i.voucherId in"
            + " (select v.id from Voucher v where v.storeMode = :mode)")
    long countOpenForStoreMode(@Param("mode") GetMoreMode storeMode);
}
