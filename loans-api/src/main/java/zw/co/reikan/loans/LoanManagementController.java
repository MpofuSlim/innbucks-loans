package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;
import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS;

@RestController
@Slf4j
@RequestMapping

@Tag(name = "BULKIT LOANS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@bulkit.co.zw_ for support.\n")
public class LoanManagementController {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private DisbursementService disbursementService;


    @Operation(summary = "DISBURSE LOANS",
            description = "Disburses a pending loan",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204",
                    description = "Request received for processing"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/disburse")
    public ResponseEntity findLoans(@PathVariable Long id) {
        log.info("Disbursing loan: {}", id);
        final Loan loan = loanRepository.findById(id).orElseThrow();
        if (loan.getDisbursementStatus() == SUCCESS) {
            log.info("Loan already disbursed");
            return ResponseEntity.badRequest().build();
        }
        disbursementService.processDisbursement(DisbursementRequest.builder()
                .amount(loan.getDisbursedAmount())
                .mobileNumber(loan.getMobileNumber())
                .reference(loan.getReference())
                .build(), loan);
        return ResponseEntity.noContent().build();
    }
}
