package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, Long> {

    Optional<VoucherRedemption> findByMerchantIdAndMerchantReference(Long merchantId, String merchantReference);

    List<VoucherRedemption> findByVoucherIdOrderByIdAsc(Long voucherId);

    boolean existsByVoucherId(Long voucherId);

    List<VoucherRedemption> findByVoucherIdInOrderByIdAsc(Collection<Long> voucherIds);

    /** A till's redemptions in the window, by when they were made. */
    List<VoucherRedemption> findBySourceAndRedeemedAtBetweenOrderByIdAsc(VoucherRedemptionSource source,
                                                                        LocalDateTime from, LocalDateTime to);

    /** GetMore's sales read back in the window, by when loans recorded them, whenever GetMore made them. */
    List<VoucherRedemption> findBySourceAndRecordedAtBetweenOrderByIdAsc(VoucherRedemptionSource source,
                                                                        LocalDateTime from, LocalDateTime to);
}
