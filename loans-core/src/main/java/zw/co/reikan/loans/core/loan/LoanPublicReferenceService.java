package zw.co.reikan.loans.core.loan;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Slf4j
@Service
public class LoanPublicReferenceService {

    static final String SEQUENCE = "loan_public_ref_seq";

    @PersistenceContext
    private EntityManager entityManager;

    @PostConstruct
    @Transactional
    void ensureSequenceExists() {
        try {
            entityManager.createNativeQuery("CREATE SEQUENCE IF NOT EXISTS " + SEQUENCE + " START WITH 1")
                    .executeUpdate();
        } catch (Exception e) {
            // Non-fatal on read replicas / restricted users: DDL also ships in docs/db.
            log.warn("Could not ensure {} exists (apply docs/db/enterprise_hardening.sql manually): {}",
                    SEQUENCE, e.getMessage());
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
