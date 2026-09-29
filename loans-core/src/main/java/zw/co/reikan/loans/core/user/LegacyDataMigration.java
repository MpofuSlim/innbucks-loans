package zw.co.reikan.loans.core.user;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Brings stored user groups in line with {@link UserGroup} before anything reads a user.
 *
 * <p>Groups are stored by name ({@code user_groups.user_group}), so a user still holding a group
 * the enum no longer has cannot be loaded at all: sign-in, the user lists and every token check
 * would fail for them. The groups retired with the merchant sales network are therefore rewritten
 * here, before JPA starts (the entity manager factory depends on this bean):</p>
 * <ul>
 *   <li>{@code SUB_AGENTS} become {@code AGENTS}: a sales consultant captured applications, which
 *       is what a field agent does, and keeps the loans they originated.</li>
 *   <li>{@code ORGANISATION_SUPER_USER} and {@code RETAIL_SALES} are removed. Nothing in the SSB
 *       workflow corresponds to them; an account left with no group is named in a WARN for an
 *       administrator to reassign or retire.</li>
 * </ul>
 *
 * <p>Every changed account's {@code token_version} is bumped, so its tokens carrying the old group
 * stop working at once and the next sign-in carries the new one. Hibernate's CHECK constraint on
 * the column lists the old names, so it is dropped first and recreated from the current enum. It
 * all runs in one transaction; if it fails the application refuses to start rather than run with
 * users it cannot load. With nothing to migrate it changes nothing, so every later boot is a
 * no-op, and on a fresh database (no {@code user_groups} yet) it does nothing at all.</p>
 */
@Slf4j
@Component
public class LegacyDataMigration {

    /** A retired group, and the group its holders now hold. */
    static final Map<String, String> RENAMED_GROUPS = Map.of("SUB_AGENTS", UserGroup.AGENTS.name());

    /** Retired groups with no successor. */
    static final Set<String> REMOVED_GROUPS = Set.of("ORGANISATION_SUPER_USER", "RETAIL_SALES");

    static final String CHECK_CONSTRAINT = "user_groups_user_group_check";

    private final DataSource dataSource;

    @Autowired
    public LegacyDataMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                migrateUserGroups(connection);
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not migrate retired user groups " + retiredGroups()
                    + "; refusing to start, because users still holding them cannot be loaded", ex);
        }
    }

    private void migrateUserGroups(Connection connection) throws SQLException {
        if (!tableExists(connection, "user_groups")) {
            return;
        }
        List<Long> affected = queryLongs(connection,
                "SELECT DISTINCT user_id FROM user_groups WHERE user_group IN " + placeholders(retiredGroups().size()),
                retiredGroups());
        if (affected.isEmpty()) {
            return;
        }

        boolean droppedCheck = dropUserGroupChecks(connection);

        Map<String, Integer> moved = new LinkedHashMap<>();
        for (Map.Entry<String, String> rename : RENAMED_GROUPS.entrySet()) {
            // A user holding both keeps one row: the (user_id, user_group) key allows no duplicate.
            update(connection, "DELETE FROM user_groups WHERE user_group = ? AND user_id IN"
                    + " (SELECT user_id FROM user_groups WHERE user_group = ?)", rename.getKey(), rename.getValue());
            moved.put(rename.getKey(), update(connection,
                    "UPDATE user_groups SET user_group = ? WHERE user_group = ?", rename.getValue(), rename.getKey()));
        }
        int removed = update(connection, "DELETE FROM user_groups WHERE user_group IN "
                + placeholders(REMOVED_GROUPS.size()), REMOVED_GROUPS.toArray());

        if (droppedCheck) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE user_groups ADD CONSTRAINT " + CHECK_CONSTRAINT
                        + " CHECK (user_group IN (" + Arrays.stream(UserGroup.values())
                        .map(group -> "'" + group.name() + "'").collect(Collectors.joining(", ")) + "))");
            }
        }

        boolean signedOut = columnExists(connection, "user", "token_version");
        if (signedOut) {
            update(connection, "UPDATE \"user\" SET token_version = COALESCE(token_version, 0) + 1 WHERE id IN "
                    + placeholders(affected.size()), affected.toArray());
        }

        List<String> groupless = queryStrings(connection, "SELECT username FROM \"user\" u WHERE u.id IN "
                + placeholders(affected.size())
                + " AND NOT EXISTS (SELECT 1 FROM user_groups g WHERE g.user_id = u.id) ORDER BY username",
                affected);

        log.info("Migrated retired user groups for {} account(s): renamed {}, removed {} row(s) of {}{}",
                affected.size(), describe(moved), removed, new TreeSet<>(REMOVED_GROUPS),
                signedOut ? "; their existing sessions have ended" : "");
        if (!groupless.isEmpty()) {
            log.warn("{} account(s) now hold no group, because theirs was retired: {}. They can still sign in"
                            + " but may only use what any signed-in user may. Assign a group, or retire the account.",
                    groupless.size(), groupless);
        }
    }

    private static Set<String> retiredGroups() {
        Set<String> retired = new TreeSet<>(RENAMED_GROUPS.keySet());
        retired.addAll(REMOVED_GROUPS);
        return retired;
    }

    /** Drops any CHECK on {@code user_groups} naming the column; PostgreSQL only, where Hibernate makes one. */
    private static boolean dropUserGroupChecks(Connection connection) throws SQLException {
        if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            return false;
        }
        List<String> checks = queryStrings(connection, """
                SELECT con.conname FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                WHERE rel.relname = 'user_groups' AND ns.nspname = current_schema() AND con.contype = 'c'
                  AND pg_get_constraintdef(con.oid) LIKE '%user_group%'
                """, List.of());
        try (Statement statement = connection.createStatement()) {
            for (String check : checks) {
                statement.execute("ALTER TABLE user_groups DROP CONSTRAINT \"" + check.replace("\"", "\"\"") + "\"");
            }
        }
        return true;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), connection.getSchema(), table, null)) {
            return tables.next();
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), connection.getSchema(), table, column)) {
            return columns.next();
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, Arrays.asList(parameters));
            return statement.executeUpdate();
        }
    }

    private static List<Long> queryLongs(Connection connection, String sql, Collection<?> parameters)
            throws SQLException {
        List<Long> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getLong(1));
                }
            }
        }
        return values;
    }

    private static List<String> queryStrings(Connection connection, String sql, Collection<?> parameters)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static void bind(PreparedStatement statement, Collection<?> parameters) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            statement.setObject(index++, parameter);
        }
    }

    private static String placeholders(int count) {
        return "(" + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    private static String describe(Map<String, Integer> moved) {
        return moved.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey() + " -> " + RENAMED_GROUPS.get(entry.getKey()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Runs the migration before the entity manager factory, so no user is read under a retired group. */
    @Configuration(proxyBeanMethods = false)
    static class BeforeJpa {

        @Bean
        static EntityManagerFactoryDependsOnPostProcessor legacyDataMigrationBeforeJpa() {
            return new EntityManagerFactoryDependsOnPostProcessor(LegacyDataMigration.class);
        }
    }
}
