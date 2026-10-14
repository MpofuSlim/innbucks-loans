package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface VoucherRefundRepository extends JpaRepository<VoucherRefund, Long> {

    List<VoucherRefund> findByVoucherIdOrderByIdAsc(Long voucherId);

    boolean existsByVoucherId(Long voucherId);

    List<VoucherRefund> findByVoucherIdInOrderByIdAsc(Collection<Long> voucherIds);

    /** Refunds read back in the window: what a day's settlement report lists, whenever GetMore made them. */
    List<VoucherRefund> findByRecordedAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);
}
