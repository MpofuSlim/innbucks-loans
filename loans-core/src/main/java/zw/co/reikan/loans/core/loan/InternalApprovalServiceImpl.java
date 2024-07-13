package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.keycloak.KeyCloakServiceImpl;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
@Service
public class InternalApprovalServiceImpl implements InternalApprovalService {
    private final LoanRepository loanRepository;
    private final KeyCloakServiceImpl keyCloakService;
    private final LoanMapper loanMapper;

    @Override
    public InternalApprovalResponse approveLoan(InternalApprovalRequest request, Long id) {

        Loan loan = loanRepository.findById(id).orElseThrow();

        if (loan.getInternalApprovalStatus() != InternalApprovalStatus.PENDING
                && loan.getInternalApprovalStatus() != null) {
            throw new RuntimeException("Loan already approved");
        }

        if (loan.getLoanApprovalStatus() != LoanApprovalStatus.APPROVED) {
            throw new RuntimeException(String.format("Loan with status %s cannot be approved", loan.getLoanApprovalStatus()));
        }

        loan.setInternalApprovalStatus(request.getStatus());
        loan.setInternalApprovalDate(LocalDateTime.now());
        loan.setInternalApprovalComment(request.getComment());
        String username = keyCloakService.getLoggedInUsername();
        log.info("Logged in username: {}", username);
        loan.setInternalApprovalBy(username);
        Loan savedLoan = loanRepository.save(loan);
        InternalApprovalResponse response = new InternalApprovalResponse("Approved successfully");
        response.setLoan(loanMapper.fromLoan(savedLoan));
        return response;
    }
}
