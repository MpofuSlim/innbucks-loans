package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.api.CreateAgentRequest;
import zw.co.reikan.loans.core.api.CreateUserResponse;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.keycloak.KeycloakService;
import zw.co.reikan.loans.core.loan.LoanBatchRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.user.CreateUserService;
import zw.co.reikan.loans.core.user.SaveUserResponse;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.ArrayList;
import java.util.List;

import static zw.co.reikan.loans.core.StartupTask.FAVORING_BULK_IT;

@Tag(name = "TRUNCATE")
@RestController
@RequiredArgsConstructor
@Slf4j
@Profile("test-environment")
public class TruncationController {

    private final UserRepository userRepository;
    private final LoanRepository loanRepository;
    private final LoanBatchRepository loanBatchRepository;
    private final KeycloakService keycloakService;
    private final MerchantRepository merchantRepository;
    private final CreateUserService createUserService;
    private final CommissionGroupRepository commissionGroupRepository;


    @PostMapping({"/auth/6fab0a61-637b-4cb9-a9ac-ddf61af32400"})
    public ResponseEntity<SaveUserResponse> createAgent(@RequestBody CreateAgentRequest createUserRequest) {

        Long defaultComission = commissionGroupRepository.findByNameIgnoreCase(FAVORING_BULK_IT)
                .orElseThrow(() -> new ValidationException("Default commission group not found")).getId();

        createUserRequest.setCommissionGroupId(defaultComission);

        CreateUserResponse createUserResponse = createUserService.create(createUserRequest, null,
                Merchant.DEFAULT_MERCHANT_CODE);

        SaveUserResponse saveUserResponse = new SaveUserResponse();
        saveUserResponse.setUser(createUserResponse.user());
        return ResponseEntity.ok(saveUserResponse);
    }


    @GetMapping("/auth/6fab0a61-637b-4cb9-a9ac-ddf61af3202a")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity truncate(@RequestParam(required = false, defaultValue = "false") Boolean deleteBatches,
                                   @RequestParam(required = false, defaultValue = "false") Boolean deleteLoans,
                                   @RequestParam(required = false, defaultValue = "false") Boolean deleteUsers,
                                   @RequestParam(required = false, defaultValue = "false") Boolean deleteMerchants
    ) {
        if (deleteBatches) {
            log.info("Deleting loan batches");
            loanBatchRepository.deleteAll();
        }

        if (deleteLoans) {
            log.info("Deleting loans");
            loanRepository.deleteAll();
        }

        if (deleteUsers) {
            log.info("Deleting users");
            List<User> all = userRepository.findAll();
            deleteUsers(all);
        }

        if (deleteMerchants) {
            merchantRepository.findAll()
                    .forEach(m -> {
                        if (!m.getCompanyName().equalsIgnoreCase(Merchant.DEFAULT_MERCHANT_NAME)) {
                            merchantRepository.deleteById(m.getId());
                        }
                    });
        }

        return ResponseEntity.ok().build();
    }

    private void deleteUsers(List<User> users) {
        List<User> referencedAgents = new ArrayList<>();
        users.forEach(u -> {
                    if (!u.getUsername().equalsIgnoreCase(User.SYSTEM_USER_NAME)) {
                        log.info("Deleting user {}", u.getUsername());
                        keycloakService.deleteUser(u.getExternalSystemId());
                        try {
                            userRepository.delete(u);
                        } catch (DataIntegrityViolationException e) {
                            log.error("Error deleting user {}", u.getUsername(), e);
                            referencedAgents.add(u);
                        }
                    }
                }
        );
        if (referencedAgents.isEmpty()) {
            return;
        }
        deleteUsers(referencedAgents);
    }
}
