package zw.co.reikan.loans.core.loan;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Generates gap-tolerant, human-facing sequential loan references of the form
 * {@code LN-2026-00042}, backed by a PostgreSQL sequence (concurrency-safe
 * across nodes — no SELECT MAX + 1 race).
 *
 * <p>ADDITIVE by design: the existing internal reference
 * ({@link Loan#getReference()} — the zero-padded id used by the Ndasenda SSB
 * integration) is untouched. This public reference is a new, separate
 * identifier for statements, receipts and customer support.</p>
 *
 * <p>Both the saga's DISBURSED step and every bulk-upload row draw a reference inside their
 * own transaction, so a missing sequence rolls back the ledger posting and the bulk row with it.
 * The sequence is therefore created at startup, and a boot that cannot create or find it says so
 * at ERROR.</p>
 */
@Slf4j
@Service
public class LoanPublicReferenceService {

    static final String SEQUENCE = "loan_public_ref_seq";

    @PersistenceContext
    private EntityManager entityManager;

    private final JdbcOperations jdbc;

    @Autowired
    public LoanPublicReferenceService(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    LoanPublicReferenceService(JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs on a plain auto-committed connection. This used to be {@code @Transactional} on the
     * {@code @PostConstruct} method, which Spring never applies (the call does not go through the
     * proxy), so the DDL ran with no transaction and failed on every boot: a fresh database never
     * got the sequence.
     */
    @PostConstruct
    void ensureSequenceExists() {
        try {
            jdbc.execute("CREATE SEQUENCE IF NOT EXISTS " + SEQUENCE + " START WITH 1");
            return;
        } catch (RuntimeException ex) {
            if (sequenceExists()) {
                // A user without CREATE rights on a database where the sequence was made by hand.
                log.info("Could not create {} ({}), but it already exists", SEQUENCE, ex.getClass().getSimpleName());
                return;
            }
            log.error("LOAN REFERENCE SEQUENCE MISSING: {} does not exist and could not be created ({})."
                            + " Until it exists every bulk-upload row fails and the saga cannot record a"
                            + " disbursement or post it to the ledger. Create it with docs/db/enterprise_hardening.sql,"
                            + " or grant this user CREATE on the schema and restart",
                    SEQUENCE, ex.getMessage());
        }
    }

    private boolean sequenceExists() {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, SEQUENCE));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Transactional
    public String next() {
        Number value = (Number) entityManager
                .createNativeQuery("SELECT nextval('" + SEQUENCE + "')")
                .getSingleResult();
        return format(LocalDate.now(ZoneOffset.UTC).getYear(), value.longValue());
    }

    static String format(int year, long sequenceValue) {
        return String.format("LN-%d-%05d", year, sequenceValue);
    }
}
