package zw.co.innbucks.loans.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V35 clears the legacy document columns on loans only where V6's copy in loan_documents holds the same bytes.
 * Pinned on loans seeded before V6, so V6 makes the real copies, then given the states a cell can hold
 * afterwards: a copied inline value and a copied large object (cleared, the large object unlinked); a value that
 * no longer matches its copy, one with no copy, one V6 could not decode and a large object two values share (all
 * left exactly as they were); and a loan with no documents at all.
 */
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class LegacyLoanDocumentColumnsMigrationIT {

    private static final byte[] PAYSLIP = bytes("%PDF-1.7 payslip of T. Moyo");
    private static final byte[] NATIONAL_ID = bytes("\u0089PNG national id of T. Moyo");
    private static final byte[] SIGNATURE = bytes("\u0089PNG signature of T. Moyo");
    private static final byte[] WITNESS = bytes("\u0089PNG signature of the witness");
    private static final byte[] OTHER = bytes("%PDF-1.7 a different payslip");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(ItPostgres.createDatabase("loans_v35_it"), ItPostgres.user(),
                ItPostgres.password());
        jdbc = new JdbcTemplate(dataSource);
    }

    /** The application's migrations, up to and including {@code target}. */
    private void migrateTo(String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    @DisplayName("only a value whose copy holds the same bytes is cleared; everything else stays as it was")
    void clearsOnlyVerifiedCopies() {
        migrateTo("5");
        // Copied by V6, inline (a data: URL with line breaks, as a browser sends it) and as a large object.
        long copied = loan(dataUrl(PAYSLIP), base64(NATIONAL_ID), null, null);
        long signatureObject = largeObject(base64(SIGNATURE));
        long copiedObject = loan(null, null, Long.toString(signatureObject), null);
        // Copied, then changed afterwards: its copy no longer holds the same bytes. Its signature still matches.
        long changed = loan(base64(PAYSLIP), null, base64(SIGNATURE), null);
        // V6 could not decode it, so there is no copy.
        long undecodable = loan(null, "not base64 at all!", null, null);
        // One large object named by two loans: both copied, but unlinking it for one would take it from the other.
        long witnessObject = largeObject(base64(WITNESS));
        long sharedA = loan(null, null, null, Long.toString(witnessObject));
        long sharedB = loan(null, null, null, Long.toString(witnessObject));
        long empty = loan(null, null, null, null);

        migrateTo("6");
        assertThat(documentTypes(copied)).containsExactlyInAnyOrder("PAYSLIP", "NATIONAL_ID");
        assertThat(documentTypes(copiedObject)).containsExactly("SIGNATURE");
        assertThat(documentTypes(undecodable)).isEmpty();
        assertThat(documentTypes(sharedA)).containsExactly("WITNESS_SIGNATURE");

        jdbc.update("UPDATE loans SET payslip_picture = ? WHERE id = ?", base64(OTHER), changed);
        // A value with no copy at all.
        long noCopy = loan(base64(PAYSLIP), null, null, null);
        long documentsBefore = count("SELECT count(*) FROM loan_documents");
        Map<String, Object> versionsBefore = versions();

        migrateTo("35");

        assertThat(legacy(copied)).containsOnlyNulls();
        assertThat(legacy(copiedObject)).containsOnlyNulls();
        assertThat(largeObjectExists(signatureObject)).as("the copied large object is unlinked").isFalse();

        assertThat(legacy(changed)).containsExactly(base64(OTHER), null, null, null);
        assertThat(legacy(undecodable)).containsExactly(null, "not base64 at all!", null, null);
        assertThat(legacy(noCopy)).containsExactly(base64(PAYSLIP), null, null, null);
        assertThat(legacy(sharedA)).containsExactly(null, null, null, Long.toString(witnessObject));
        assertThat(legacy(sharedB)).containsExactly(null, null, null, Long.toString(witnessObject));
        assertThat(largeObjectExists(witnessObject)).as("a shared large object stays").isTrue();
        assertThat(legacy(empty)).containsOnlyNulls();

        // The copies themselves, and every loan's version and last-modified date, are untouched.
        assertThat(count("SELECT count(*) FROM loan_documents")).isEqualTo(documentsBefore);
        assertThat(versions()).isEqualTo(versionsBefore);
    }

    @Test
    @DisplayName("an empty table, and one with no legacy values: nothing to do")
    void nothingToClear() {
        migrateTo("33");
        long loan = loan(null, null, null, null);

        migrateTo("35");

        assertThat(legacy(loan)).containsOnlyNulls();
    }

    private long loan(String payslip, String nationalId, String signature, String witnessSignature) {
        return jdbc.queryForObject("""
                INSERT INTO loans (payslip_picture, national_id_picture, signature, witness_signature, created_by,
                                   created_date, last_modified_date, version)
                VALUES (?, ?, ?, ?, 'agent.jane', now(), now() - interval '1 day', 3) RETURNING id
                """, Long.class, payslip, nationalId, signature, witnessSignature);
    }

    /** As Hibernate stored a @Lob String: the base64 in a large object, whose OID is the column's value. */
    private long largeObject(String base64) {
        return jdbc.queryForObject("SELECT lo_from_bytea(0, convert_to(?, 'UTF8'))", Long.class, base64);
    }

    private boolean largeObjectExists(long oid) {
        return count("SELECT count(*) FROM pg_largeobject_metadata WHERE oid = " + oid) > 0;
    }

    private List<String> legacy(long loan) {
        return jdbc.queryForObject("SELECT payslip_picture, national_id_picture, signature, witness_signature"
                        + " FROM loans WHERE id = ?",
                (row, n) -> Arrays.asList(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4)), loan);
    }

    private List<String> documentTypes(long loan) {
        return jdbc.queryForList("SELECT document_type FROM loan_documents WHERE loan_id = ?", String.class, loan);
    }

    private Map<String, Object> versions() {
        return Map.of("rows", jdbc.queryForList("SELECT id, version, last_modified_date FROM loans ORDER BY id"));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static String base64(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }

    private static String dataUrl(byte[] content) {
        String encoded = base64(content);
        return "data:application/pdf;base64," + encoded.substring(0, 10) + "\r\n" + encoded.substring(10);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
