package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SimStoreRepository extends JpaRepository<SimStore, Integer> {

    /**
     * The outlet, made unless it exists: two first sales at a new outlet at once both succeed (the second waits for the
     * first's commit, then finds its row).
     */
    @Modifying
    @Query(value = "INSERT INTO getmore_sim_stores (outlet_code, outlet_name, created_at) VALUES (:code, :name, :at)"
            + " ON CONFLICT (outlet_code) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("code") String outletCode, @Param("name") String outletName,
                       @Param("at") LocalDateTime at);

    Optional<SimStore> findByOutletCode(String outletCode);
}
