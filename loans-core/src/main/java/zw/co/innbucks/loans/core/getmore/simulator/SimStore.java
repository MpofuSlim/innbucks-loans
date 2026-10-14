package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * An outlet of the simulated store, made on its first sale ({@link SimStoreRepository#insertIfAbsent}): Magento's
 * {@code store_id} and the middle line of an order's {@code store_name}.
 */
@Entity
@Table(name = "getmore_sim_stores")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SimStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Integer storeId;

    @Column(name = "outlet_code", length = 64, nullable = false)
    private String outletCode;

    @Column(name = "outlet_name", length = 120, nullable = false)
    private String outletName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Magento's {@code store_name}: website, store and store view, one per line. */
    public String magentoStoreName() {
        return "GetMore\n" + outletName + "\nDefault Store View";
    }
}
