package zw.co.innbucks.loans.core.borrower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface BorrowerAssertionUseRepository extends JpaRepository<BorrowerAssertionUse, String> {

    @Modifying
    @Query("delete from BorrowerAssertionUse u where u.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
