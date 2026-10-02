package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, Long> {

    Optional<VoucherRedemption> findByMerchantIdAndMerchantReference(Long merchantId, String merchantReference);

    List<VoucherRedemption> findByVoucherIdOrderByIdAsc(Long voucherId);

    List<VoucherRedemption> findByRedeemedAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);
}
