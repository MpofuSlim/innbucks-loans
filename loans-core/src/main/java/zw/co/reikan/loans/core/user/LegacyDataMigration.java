package zw.co.reikan.loans.core.user;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.StartupTask;

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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Brings stored data in line with the names the code now uses, before anything reads it.
 *
 * <p>Groups are stored by name ({@code user_groups.user_group}), so a user still holding a group
 * the enum no longer has cannot be loaded at all: sign-in, the user lists and every token check
 * would fail for them. Retired groups are therefore rewritten here, before JPA starts (the entity
 * manager factory depends on this bean):</p>
 * <ul>
 *   <li>{@code BULKIT_ADMIN} becomes {@code SUPER_ADMIN}: the same authority, named for this
 *       platform rather than the product the code came from.</li>
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
 * no-op, and on a fresh database (no tables yet) it does nothing at all.</p>
 *
 * <p>The seeded commission groups are renamed in the same transaction, from their
 * {@code ...-BulkIT} names to the ones {@link StartupTask} seeds. Merchants and users point at the
 * rows by id, so renaming keeps them; without it the next boot would seed a second set under the
 * new names and leave everyone on the old one.</p>
 */
@Slf4j
@Component
public class LegacyDataMigration {

    /** A retired group, and the group its holders now hold. */
    static final Map<String, String> RENAMED_GROUPS = Map.of(
            "BULKIT_ADMIN", UserGroup.SUPER_ADMIN.name(),
            "SUB_AGENTS", UserGroup.AGENTS.name());

    /** Retired groups with no successor. */
    static final Set<String> REMOVED_GROUPS = Set.of("ORGANISATION_SUPER_USER", "RETAIL_SALES");

    /** A seeded commission group's old name, and the name {@link StartupTask} now seeds it under. */
    static final Map<String, String> RENAMED_COMMISSION_GROUPS = Map.of(
            "80-20-Favouring-BulkIT", StartupTask.EIGHTY_TWENTY,
            "100-Favouring-BulkIT", StartupTask.FAVOURING_INNBUCKS,
            "Default-BulkIT", StartupTask.ZERO_BASED_DEFAULT);

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
                renameCommissionGroups(connection);
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not migrate retired user groups " + retiredGroups()
                    + " and commission group names; refusing to start, because users still holding a retired"
                    + " group cannot be loaded", ex);
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

        // The entity's table: "User" under the quoted identifiers this service is configured with.
        String userTable = tableNamed(connection, "user");
        String users = quoted(userTable);
        boolean signedOut = columnExists(connection, userTable, "token_version");
        if (signedOut) {
            update(connection, "UPDATE " + users + " SET token_version = COALESCE(token_version, 0) + 1 WHERE id IN "
                    + placeholders(affected.size()), affected.toArray());
        }

        List<String> groupless = queryStrings(connection, "SELECT username FROM " + users + " u WHERE u.id IN "
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

    private void renameCommissionGroups(Connection connection) throws SQLException {
        if (!tableExists(connection, "commission_group")) {
            return;
        }
        List<String> renamed = new ArrayList<>();
        for (Map.Entry<String, String> rename : new TreeMap<>(RENAMED_COMMISSION_GROUPS).entrySet()) {
            boolean oldExists = count(connection, rename.getKey()) > 0;
            if (!oldExists) {
                continue;
            }
            if (count(connection, rename.getValue()) > 0) {
                log.warn("Commission groups '{}' and '{}' both exist, so '{}' was not renamed: merchants and users"
                                + " on it stay there. Move them to '{}' and delete it.",
                        rename.getKey(), rename.getValue(), rename.getKey(), rename.getValue());
                continue;
            }
            update(connection, "UPDATE commission_group SET name = ? WHERE lower(name) = lower(?)",
                    rename.getValue(), rename.getKey());
            renamed.add(rename.getKey() + " -> " + rename.getValue());
        }
        if (!renamed.isEmpty()) {
            log.info("Renamed commission group(s) {}", renamed);
        }
    }

    private static int count(Connection connection, String commissionGroup) throws SQLException {
        List<Long> counts = queryLongs(connection,
                "SELECT COUNT(*) FROM commission_group WHERE lower(name) = lower(?)", List.of(commissionGroup));
        return counts.getFirst().intValue();
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

    /** The table's name as the database holds it, matched ignoring case; the user table has held both. */
    private static String tableNamed(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet tables = metaData.getTables(connection.getCatalog(), connection.getSchema(), "%",
                new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (table.equalsIgnoreCase(name)) {
                    return name;
                }
            }
        }
        throw new SQLException("No " + table + " table in schema " + connection.getSchema());
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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
