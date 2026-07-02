package zw.co.reikan.loans.core.bulk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.files.FileSignatureValidator;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanPublicReferenceService;
import zw.co.reikan.loans.core.loan.LoanRequest;
import zw.co.reikan.loans.core.loan.LoanService;

import java.util.Optional;

/**
 * Processes exactly ONE loan application inside a bulk run.
 *
 * <p>Separate bean (not a private method) so {@code REQUIRES_NEW} proxying is
 * real: each item commits or rolls back its own transaction. When item #14
 * dies on a validation failure or an Ndasenda/InnBucks outage, only item #14's
 * transaction rolls back — the surrounding chunk and every other item proceed
 * untouched. This is the blast-radius boundary.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLoanItemProcessor {

    private final LoanService loanService;
    private final LoanPublicReferenceService publicReferenceService;
    private final FileSignatureValidator fileSignatureValidator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BulkLoanItemOutcome process(int index, LoanRequest request) {
        // Zero-trust content: magic-number checks on any attached documents
        // BEFORE the application touches business state.
        fileSignatureValidator.requireAcceptedBase64Document("nationalIdPicture", request.getNationalIdPicture());
        fileSignatureValidator.requireAcceptedBase64Document("payslipPicture", request.getPayslipPicture());

        // Reuse the UNCHANGED single-application flow — bulk is an orchestration
        // layer over existing behaviour, not a second code path for loans.
        LoanResponse response = loanService.requestLoan(request);

        String publicReference = null;
        if (response.getInternalReference() != null) {
            Optional<Loan> created = loanService.findByReference(response.getInternalReference());
            if (created.isPresent() && created.get().getPublicReference() == null) {
                created.get().setPublicReference(publicReferenceService.next());
                publicReference = created.get().getPublicReference();
            }
        }
        return BulkLoanItemOutcome.ok(index, response.getInternalReference(), publicReference);
    }
}
