package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The pending-application check's expression indexes: created at startup, never fatal. */
class LoanDuplicateCheckIndexesTest {

    @Test
    @DisplayName("both upper() indexes are created, idempotently")
    void createsBothIndexes() {
        JdbcOperations jdbc = mock(JdbcOperations.class);

        new LoanDuplicateCheckIndexes(jdbc).ensureIndexes();

        verify(jdbc).execute("CREATE INDEX IF NOT EXISTS idx_loan_request_upper_ec_number ON loan_request (upper(ec_number))");
        verify(jdbc).execute("CREATE INDEX IF NOT EXISTS idx_loan_request_upper_national_id"
                + " ON loan_request (upper(national_id_number))");
    }

    @Test
    @DisplayName("a user who may not create indexes still starts; the second index is still tried")
    void failureIsNeverFatal() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        doThrow(new DataAccessResourceFailureException("permission denied for schema public"))
                .when(jdbc).execute(anyString());

        assertThatCode(() -> new LoanDuplicateCheckIndexes(jdbc).ensureIndexes()).doesNotThrowAnyException();

        verify(jdbc, times(2)).execute(anyString());
    }
}
