package zw.co.reikan.loans.loansapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import zw.co.reikan.loans.core.LoansCoreConfig;

@SpringBootApplication
public class LoansApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoansApiApplication.class, args);
    }

}
