package zw.co.reikan.loans;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import zw.co.reikan.loans.core.LoansCoreConfig;

@SpringBootApplication
public class LoansEmbeddedUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoansEmbeddedUiApplication.class, args);
    }

}
