package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreModeReport;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;

import java.util.Objects;

/**
 * Seeds the simulated store's one sales rule (rule {@code GETMORE_RULE_ID}) at boot, if it is missing, and only in
 * SIMULATED: a migration would write it into every cell's database, production's included. The rule is active,
 * {@code cart_fixed} worth 0 (each coupon carries its own allowance, assumption A1), AUTO coupons, unlimited uses per
 * coupon (spending in parts needs them), no dates.
 *
 * <p>It takes {@link GetMoreModeReport} as a constructor dependency, so SIMULATED's boot checks (node, live keys, key
 * strength) always run before this writes anything: a pod refused by them never inserts a row into the database it was
 * pointed at. That order is structural, not an accident of which bean the context happens to create first.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimulatedStoreSeeder {

    static final String RULE_NAME = "InnBucks Staff Grocery (SIMULATED)";

    private final SimRuleRepository rules;
    private final TransactionTemplate transaction;
    private final GetMoreProperties properties;
    private final MarketTimeZone marketTimeZone;

    public SimulatedStoreSeeder(SimRuleRepository rules, PlatformTransactionManager transactionManager,
                                GetMoreProperties properties, MarketTimeZone marketTimeZone,
                                GetMoreModeReport modeReport) {
        Objects.requireNonNull(modeReport, "modeReport");
        this.rules = rules;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
    }

    @PostConstruct
    void seedAtBoot() {
        seed();
    }

    /** Inserts the rule unless it is there; whether it was inserted. Safe to call again. */
    public boolean seed() {
        long ruleId = properties.getRuleId();
        Integer inserted = transaction.execute(
                status -> rules.seedIfAbsent(ruleId, RULE_NAME, marketTimeZone.nowUtc()));
        boolean seeded = inserted != null && inserted > 0;
        if (seeded) {
            log.info("GetMore simulator: seeded sales rule {} ({})", ruleId, RULE_NAME);
        }
        return seeded;
    }
}
