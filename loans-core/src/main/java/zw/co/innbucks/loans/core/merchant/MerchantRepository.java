package zw.co.innbucks.loans.core.merchant;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByMerchantCode(String code);

    Boolean existsByMerchantCode(String code);

    /** The Staff Grocery Loan's merchant, when one is set. */
    Optional<Merchant> findByStaffLoanMerchantTrue();

    /** The same, locked, so two changes of merchant cannot both clear the one they read. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Merchant m where m.staffLoanMerchant = true")
    Optional<Merchant> lockStaffLoanMerchant();
}
