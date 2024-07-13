package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.loan.*;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@RestController
@Slf4j
@RequestMapping

@Tag(name = "BULKIT LOANS")
public class InternalLoanApplicationController {

    @Autowired
    private LoanService loanService;

    @Operation(summary = "APPLY FOR LOAN",
            description = "Apply for loan",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans")
    public LoanResponse create(@RequestBody LoanRequest request) {
        log.info("Create loan request: {}", request);
        return loanService.requestLoan(request);
    }

    @Operation(summary = "LOAN CALCULATOR",
            description = "loan calculator",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/calculate")
    public LoanDetails calculate(@RequestBody LoanRequest request) {
        log.info("Calculate loan request: {}", request);
        return loanService.calculate(request);
    }

}
