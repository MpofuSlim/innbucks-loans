package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VoucherDeliveryRepository extends JpaRepository<VoucherDelivery, Long> {

    List<VoucherDelivery> findByVoucherIdOrderByIdAsc(Long voucherId);
}
