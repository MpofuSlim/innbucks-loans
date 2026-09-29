package zw.co.innbucks.loans;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import zw.co.innbucks.loans.core.loan.LoanPublicReferenceService;

import static org.assertj.core.api.Assertions.assertThat;

// The packaged profiles plus "test": this boots on the placeholder jwt.secret,
// which is refused unless a dev/test/local/it profile is active.
@SpringBootTest
@ActiveProfiles({"api", "test"})
class LoansApiApplicationTests {

    @Autowired
    private LoanPublicReferenceService publicReferenceService;

    @Test
    void contextLoads() {
    }

    /** On a fresh database too: the sequence is created by the migrations, not by a manual script. */
    @Test
    void publicLoanReferencesCanBeDrawn() {
        assertThat(publicReferenceService.next()).matches("LN-\\d{4}-\\d{5,}");
    }

}
