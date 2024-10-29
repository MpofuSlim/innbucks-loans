package zw.co.reikan.loans.core.merchant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByMerchantCode(String code);

    Boolean existsByMerchantCode(String code);
}
