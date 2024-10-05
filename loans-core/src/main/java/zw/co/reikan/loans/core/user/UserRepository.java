package zw.co.reikan.loans.core.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    @Query("SELECT u FROM User u WHERE (:merchantId IS NULL OR u.merchant.id= :merchantId)")
    List<User> listAllUsersByMerchant(@Param("merchantId") Long merchantId);

}
