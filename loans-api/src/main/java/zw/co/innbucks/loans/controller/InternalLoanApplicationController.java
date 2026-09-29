package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.LoanResponse;
import zw.co.innbucks.loans.core.loan.LoanApplicationChecks;
import zw.co.innbucks.loans.core.loan.LoanDetails;
import zw.co.innbucks.loans.core.loan.LoanRequest;
import zw.co.innbucks.loans.core.loan.LoanService;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;
import static zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl.maskEcNumber;

@RestController
@Slf4j
@RequestMapping("/api")

@Tag(name = "INNBUCKS LOANS")
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
    public LoanResponse create(
            // Default + LoanApplicationChecks: an application must carry what the
            // InnBucks step needs, reported in ONE 400. The calculator below stays
            // on Default alone so a quote needs only the loan terms.
            @Validated({Default.class, LoanApplicationChecks.class}) @RequestBody LoanRequest request) {
        // Identifiers only: the body carries the applicant's KYC and base64 documents.
        log.info("Create loan request: channel {}, ec {}, amount {}, tenor {}", request.getChannelId(),
                maskEcNumber(request.getEcnumber()), request.getAmount(), request.getTenor());
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
    public LoanDetails calculate(@Valid @RequestBody LoanRequest request) {
        log.info("Calculate loan request: amount {}, tenor {}, type {}", request.getAmount(), request.getTenor(),
                request.getType());
        //setting null logged in user to allow anonymous loan calculation
        return loanService.calculate(request, null);
    }

}
