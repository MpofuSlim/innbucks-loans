package zw.co.reikan.loans;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// The packaged profiles plus "test": this boots on the placeholder jwt.secret,
// which is refused unless a dev/test/local/it profile is active.
@SpringBootTest
@ActiveProfiles({"api", "dummy-loan-approval", "test"})
class LoansApiApplicationTests {

    @Test
    void contextLoads() {
    }

}
