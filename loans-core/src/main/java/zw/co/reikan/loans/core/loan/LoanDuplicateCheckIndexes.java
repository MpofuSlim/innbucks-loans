package zw.co.reikan.loans.core.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * The indexes behind the pending-application check. Every application looks up the applicant's other
 * loans by {@code upper(ec_number)} and by {@code upper(national_id_number)} (upper-cased because
 * older rows can carry a lower-case check letter), and the plain index on {@code ec_number} cannot
 * serve an {@code upper(...)} comparison, so each application scanned the whole loan table twice.
 *
 * <p>Expression indexes are beyond {@code ddl-auto}, so they are created here, like the public
 * reference sequence: once the application is ready (the table exists by then, even on a fresh
 * database), on a plain auto-committed connection, idempotently. A failure is a WARN rather than an
 * error: the check is still correct without them, only slower. The same statements are in
 * {@code docs/db/enterprise_hardening.sql} for a database user without CREATE rights.</p>
 */
@Slf4j
@Component
public class LoanDuplicateCheckIndexes {

    static final List<String> STATEMENTS = List.of(
            "CREATE INDEX IF NOT EXISTS idx_loan_request_upper_ec_number ON loan_request (upper(ec_number))",
            "CREATE INDEX IF NOT EXISTS idx_loan_request_upper_national_id ON loan_request (upper(national_id_number))");

    private final JdbcOperations jdbc;

    @Autowired
    public LoanDuplicateCheckIndexes(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    LoanDuplicateCheckIndexes(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        for (String statement : STATEMENTS) {
            try {
                jdbc.execute(statement);
            } catch (RuntimeException ex) {
                log.warn("Could not create a duplicate-check index ({}): the pending-application check still works,"
                                + " but scans the loan table. Run docs/db/enterprise_hardening.sql, or grant this user"
                                + " CREATE on the schema. Statement: {}",
                        ex.getClass().getSimpleName(), statement);
            }
        }
    }
}
