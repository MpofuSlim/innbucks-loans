package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VoucherSyncIssueRepository extends JpaRepository<VoucherSyncIssue, Long> {

    List<VoucherSyncIssue> findByVoucherIdOrderByIdAsc(Long voucherId);

    List<VoucherSyncIssue> findByVoucherIdAndClearedAtIsNullOrderByIdAsc(Long voucherId);

    boolean existsByVoucherIdAndBlockingTrueAndClearedAtIsNull(Long voucherId);

    /** Open issues, oldest first: what an operator has to look at. */
    Page<VoucherSyncIssue> findByClearedAtIsNullOrderByFirstSeenAtAscIdAsc(Pageable pageable);

    Page<VoucherSyncIssue> findAllByOrderByFirstSeenAtAscIdAsc(Pageable pageable);

    long countByClearedAtIsNull();
}
