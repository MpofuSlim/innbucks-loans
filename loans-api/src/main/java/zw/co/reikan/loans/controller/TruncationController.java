package zw.co.reikan.loans.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.api.CreateAgentRequest;
import zw.co.reikan.loans.core.api.CreateUserResponse;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.loan.LoanBatchRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.user.CreateUserService;
import zw.co.reikan.loans.core.user.SaveUserResponse;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;
import static zw.co.reikan.loans.core.StartupTask.FAVORING_BULK_IT;

/**
 * Test-environment fixtures. Deliberately NOT under {@code /api/auth/**}: that
 * prefix is permitAll, which left an anonymous GET able to wipe the database and
 * an anonymous POST able to mint a user of any group.
 */
@Tag(name = "TRUNCATE")
@RestController
@RequestMapping("/api/test-support")
@RequiredArgsConstructor
@Slf4j
@Profile("test-environment")
@PreAuthorize("hasRole('BULKIT_ADMIN')")
public class TruncationController {

    /** A seed is for exercising the agent flows, never for minting privileged users. */
    private static final Set<UserGroup> SEEDABLE_GROUPS = EnumSet.of(UserGroup.AGENTS, UserGroup.SUB_AGENTS);

    private final UserRepository userRepository;
    private final LoanRepository loanRepository;
    private final LoanBatchRepository loanBatchRepository;
    private final MerchantRepository merchantRepository;
    private final CreateUserService createUserService;
    private final CommissionGroupRepository commissionGroupRepository;


    @Operation(operationId = "seedTestAgent", summary = "SEED TEST AGENT",
            description = "Creates an AGENTS or SUB_AGENTS user in the default merchant. BULKIT_ADMIN only.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @PostMapping("/agents")
    public ResponseEntity<SaveUserResponse> createAgent(@Valid @RequestBody CreateAgentRequest createUserRequest) {
        if (!SEEDABLE_GROUPS.contains(createUserRequest.getGroup())) {
            throw new ValidationException("The test seed only creates " + SEEDABLE_GROUPS + " users");
        }

        Long defaultComission = commissionGroupRepository.findByNameIgnoreCase(FAVORING_BULK_IT)
                .orElseThrow(() -> new ValidationException("Default commission group not found")).getId();

        createUserRequest.setCommissionGroupId(defaultComission);

        CreateUserResponse createUserResponse = createUserService.create(createUserRequest, null,
                Merchant.DEFAULT_MERCHANT_CODE);

        SaveUserResponse saveUserResponse = new SaveUserResponse();
        saveUserResponse.setUser(createUserResponse.user());
        return ResponseEntity.ok(saveUserResponse);
    }


    @Operation(operationId = "truncateTestData", summary = "TRUNCATE TEST DATA",
            description = "Deletes the selected data sets. BULKIT_ADMIN only; POST so no link or prefetch can trigger it.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @PostMapping("/truncate")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Caller is not BULKIT_ADMIN"),
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
