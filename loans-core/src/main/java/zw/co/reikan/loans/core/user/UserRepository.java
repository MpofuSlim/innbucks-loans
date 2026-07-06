package zw.co.reikan.loans.core.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByExternalSystemId(String externalSystemId);

    List<User> findByMerchant_MerchantCode(String merchantCode);

    List<User> findByUsernameContainingIgnoreCase(String username);

    @Query("SELECT u FROM User u WHERE (:merchantId IS NULL OR u.merchant.id= :merchantId)")
    List<User> listAllUsersByMerchant(@Param("merchantId") Long merchantId);

    Page<User> findAllByAgent_Id(Long agentId, Pageable pageable);

    Long countByAgent_Id(Long agentId);

}
