package zw.co.innbucks.loans.core.config;

import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The schema belongs to Flyway ({@code db/migration}); Hibernate only checks that the entities
 * match it, and names tables and columns in snake_case.
 *
 * <p>Set here rather than in application.yml because a deployment's /app/config replaces the
 * packaged file, and these are part of the code's contract with its own migrations, not a
 * per-environment choice: a config that still said {@code ddl-auto: update} or quoted identifiers
 * would have Hibernate reshape the schema, or look for camelCase columns the migrations renamed.</p>
 */
@Configuration(proxyBeanMethods = false)
public class JpaSchemaConfig {

    /** Flyway's version for a database Hibernate built before Flyway existed: V2 upgrades it in place. */
    static final String HIBERNATE_BUILT_SCHEMA_VERSION = "1";

    @Bean
    HibernatePropertiesCustomizer flywayOwnsTheSchema() {
        return properties -> {
            properties.put(AvailableSettings.HBM2DDL_AUTO, "validate");
            properties.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, false);
            properties.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategySnakeCaseImpl.class.getName());
            properties.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, SpringImplicitNamingStrategy.class.getName());
        };
    }

    /**
     * A database with tables but no Flyway history was built by Hibernate: it is baselined at V1,
     * which it already has in its old names, and V2 renames it to the standard ones. An empty
     * database is not baselined, so it gets V1 and then V2, which finds nothing to rename.
     */
    @Bean
    FlywayConfigurationCustomizer upgradeASchemaHibernateBuilt() {
        return configuration -> configuration
                .baselineOnMigrate(true)
                .baselineVersion(HIBERNATE_BUILT_SCHEMA_VERSION)
                .baselineDescription("Schema built by Hibernate before Flyway");
    }
}
