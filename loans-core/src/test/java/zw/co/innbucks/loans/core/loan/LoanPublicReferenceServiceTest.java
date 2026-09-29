package zw.co.innbucks.loans.core.loan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcOperations;

import java.sql.SQLException;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The sequence behind every public loan reference must exist before the saga asks it for a
 * value: it draws inside its own transaction, so a missing sequence rolls back the ledger
 * posting with it. It used to be created in a {@code @Transactional}
 * {@code @PostConstruct} method, which Spring never wraps, so the DDL failed on every boot.
 * (The real DDL is exercised against PostgreSQL by {@code LoansApiApplicationTests}.)
 */
class LoanPublicReferenceServiceTest {

    private static final String CREATE = "CREATE SEQUENCE IF NOT EXISTS loan_public_ref_seq START WITH 1";

    private JdbcOperations jdbc;
    private LoanPublicReferenceService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcOperations.class);
        service = new LoanPublicReferenceService(jdbc, new MarketTimeZone("ZW"));
        logger = (Logger) LoggerFactory.getLogger(LoanPublicReferenceService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static BadSqlGrammarException permissionDenied() {
        return new BadSqlGrammarException("create sequence", CREATE,
                new SQLException("permission denied for schema public", "42501"));
    }

    @Test
    @DisplayName("creates the sequence on its own connection, with no transaction to depend on")
    void createsTheSequence() {
        service.ensureSequenceExists();

        verify(jdbc).execute(CREATE);
        verifyNoMoreInteractions(jdbc);
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("a user that cannot create it boots quietly when it was already made by hand")
    void existingSequenceIsEnough() {
        doThrow(permissionDenied()).when(jdbc).execute(CREATE);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("loan_public_ref_seq"))).thenReturn(true);

        service.ensureSequenceExists();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("neither created nor found: ERROR naming what breaks, and the boot carries on")
    void missingSequenceIsLoud() {
        doThrow(permissionDenied()).when(jdbc).execute(CREATE);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("loan_public_ref_seq"))).thenReturn(false);

        assertThatCode(service::ensureSequenceExists).doesNotThrowAnyException();

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("LOAN REFERENCE SEQUENCE MISSING", "cannot record a disbursement", "ledger");
    }

    @Test
    @DisplayName("an unreachable database on the existence check reads as missing, never as present")
    void failedExistenceCheckIsMissing() {
        doThrow(new DataAccessResourceFailureException("connection refused")).when(jdbc).execute(CREATE);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("loan_public_ref_seq")))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        service.ensureSequenceExists();

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
    }
}
