package zw.co.innbucks.loans.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import zw.co.innbucks.loans.core.staff.StaffRegisterRow;
import zw.co.innbucks.loans.core.staff.StaffRegisterVariance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V33 turns the staff register rows' and variances' serial sequences into pooled ones (INCREMENT BY 50, read
 * pooled-lo). Pinned on a database that already holds rows, as every cell does: the sequence lands above the highest
 * existing id; a block Hibernate opens never reaches below it; and a build from before V33, inserting through the
 * column default, never takes an id inside a block the new build holds.
 */
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class PooledStaffRegisterIdsMigrationIT {

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(ItPostgres.createDatabase("loans_v33_it"), ItPostgres.user(),
                ItPostgres.password());
        jdbc = new JdbcTemplate(dataSource);
    }

    /** The application's migrations, up to and including {@code target}. */
    private void migrateTo(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    @DisplayName("a populated table: the sequence moves above its highest id, and the next block starts there")
    void sequenceStartsAboveTheExistingMax() {
        migrateTo("32");
        long batch = registerBatch();
        // Rows as an IDENTITY build wrote them, then one far above the sequence (an id set by hand).
        for (int row = 1; row <= 7; row++) {
            insertRowThroughTheDefault(batch, row);
        }
        jdbc.update("INSERT INTO staff_register_rows (id, batch_id, row_number, outcome, action) VALUES (5000, ?, 8,"
                + " 'STAGED', 'CREATE')", batch);
        long reconciliation = reconciliation();
        jdbc.update("INSERT INTO staff_register_variances (id, reconciliation_id, kind, details) VALUES (912, ?,"
                + " 'UNREADABLE', '{}')", reconciliation);

        migrateTo("33");

        assertThat(increment("staff_register_rows_id_seq")).isEqualTo(StaffRegisterRow.ID_ALLOCATION);
        assertThat(increment("staff_register_variances_id_seq")).isEqualTo(StaffRegisterVariance.ID_ALLOCATION);
        long rowsBlock = nextval("staff_register_rows_id_seq");
        long variancesBlock = nextval("staff_register_variances_id_seq");
        // pooled-lo hands out nextval .. nextval+49: all of it above every row already there.
        assertThat(rowsBlock).isGreaterThan(maxId("staff_register_rows")).isGreaterThan(5000);
        assertThat(variancesBlock).isGreaterThan(maxId("staff_register_variances")).isGreaterThan(912);
        assertThat(nextval("staff_register_rows_id_seq")).isEqualTo(rowsBlock + StaffRegisterRow.ID_ALLOCATION);
    }

    @Test
    @DisplayName("an empty table, and one whose sequence ran ahead of its rows (rolled-back inserts): never backwards")
    void sequenceNeverMovesBackwards() {
        migrateTo("32");
        long ahead = jdbc.queryForObject("SELECT setval('staff_register_variances_id_seq', 7000)", Long.class);

        migrateTo("33");

        assertThat(nextval("staff_register_rows_id_seq")).isPositive();
        assertThat(nextval("staff_register_variances_id_seq")).isGreaterThan(ahead);
    }

    @Test
    @DisplayName("rollback: a pre-V33 build inserting through the column default never lands in the new build's block")
    void aPreChangeBuildInsertsWithoutCollisions() {
        migrateTo("32");
        long batch = registerBatch();
        insertRowThroughTheDefault(batch, 1);
        migrateTo("33");

        // The new build opens a block and has used part of it.
        long block = nextval("staff_register_rows_id_seq");
        for (int offset = 0; offset < 10; offset++) {
            jdbc.update("INSERT INTO staff_register_rows (id, batch_id, row_number, outcome, action) VALUES (?, ?, ?,"
                    + " 'STAGED', 'CREATE')", block + offset, batch, 100 + offset);
        }
        // The old build (IDENTITY) inserts through the column default: a value of its own, outside the block.
        List<Long> oldBuild = List.of(insertRowThroughTheDefault(batch, 200), insertRowThroughTheDefault(batch, 201));
        assertThat(oldBuild).allSatisfy(id -> assertThat(outside(id, block)).as("%d outside the block at %d", id, block)
                .isTrue());
        // And the new build's next block is clear of them too.
        long next = nextval("staff_register_rows_id_seq");
        assertThat(oldBuild).allSatisfy(id -> assertThat(outside(id, next)).as("%d outside the block at %d", id, next)
                .isTrue());
        // The rest of the first block is still free to use: nothing the old build wrote is inside it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_register_rows WHERE id BETWEEN ? AND ?",
                Long.class, block + 10, block + 49)).isZero();
    }

    /** Whether {@code id} is outside the pooled-lo block opened at {@code start}. */
    private static boolean outside(long id, long start) {
        return id < start || id >= start + StaffRegisterRow.ID_ALLOCATION;
    }

    private long registerBatch() {
        return jdbc.queryForObject("""
                INSERT INTO staff_register_batches (source, status, submitted_by, submitted_at, total_rows,
                                                    staged_rows, rejected_rows)
                VALUES ('MANUAL', 'PENDING', 'it', now(), 1, 1, 0) RETURNING id
                """, Long.class);
    }

    private long reconciliation() {
        return jdbc.queryForObject("""
                INSERT INTO staff_register_reconciliations (file_name, file_sha256, run_by, run_at, compared_fields,
                                                            payroll_rows, register_members, matched, different,
                                                            left_on_payroll, left_on_payroll_eligible,
                                                            not_on_register, not_on_payroll, not_on_payroll_eligible,
                                                            duplicates_on_payroll, unreadable_rows)
                VALUES ('payroll.csv', ?, 'it', now(), '', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) RETURNING id
                """, Long.class, "ab".repeat(32));
    }

    /** As a build from before V33 inserts: no id, so the column default draws it. */
    private long insertRowThroughTheDefault(long batch, int rowNumber) {
        return jdbc.queryForObject("INSERT INTO staff_register_rows (batch_id, row_number, outcome, action) VALUES"
                + " (?, ?, 'STAGED', 'CREATE') RETURNING id", Long.class, batch, rowNumber);
    }

    private long nextval(String sequence) {
        return jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
    }

    private long increment(String sequence) {
        return jdbc.queryForObject("SELECT increment_by FROM pg_sequences WHERE sequencename = ?", Long.class,
                sequence);
    }

    private long maxId(String table) {
        return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM " + table, Long.class);
    }
}
