package zw.co.reikan.loans.core.commission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CommissionGroupDto;
import zw.co.reikan.loans.core.api.CreateCommissionGroupRequest;
import zw.co.reikan.loans.core.exception.ValidationException;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommissionGroupServiceImpl implements CommissionGroupService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final CommissionGroupRepository commissionGroupRepository;

    @Override
    @Transactional
    public CommissionGroupDto create(CreateCommissionGroupRequest request) {
        validate(request);

        CommissionGroup saved = commissionGroupRepository.save(CommissionGroup.builder()
                .name(request.getName().trim())
                .agentCommission(request.getAgentCommission())
                .providerCommission(request.getProviderCommission())
                .percentage(Boolean.TRUE.equals(request.getPercentage()))
                .enabled(true)
                .build());

        log.info("Created commission group {}", saved.getName());
        return CommissionGroupDto.fromCommissionGroup(saved);
    }

    private void validate(CreateCommissionGroupRequest request) {
        if (request == null) {
            throw new ValidationException("Commission group request is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException("Commission group name is required");
        }
        if (request.getAgentCommission() == null || request.getProviderCommission() == null) {
            throw new ValidationException("Agent and provider commission are required");
        }
        if (request.getAgentCommission().signum() < 0 || request.getProviderCommission().signum() < 0) {
            throw new ValidationException("Commission values cannot be negative");
        }
        if (commissionGroupRepository.findByNameIgnoreCase(request.getName().trim()).isPresent()) {
            throw new ValidationException("Commission group %s already exists".formatted(request.getName().trim()));
        }
        if (Boolean.TRUE.equals(request.getPercentage())
                && request.getAgentCommission().add(request.getProviderCommission()).compareTo(ONE_HUNDRED) != 0) {
            throw new ValidationException("Agent and provider percentages must add up to 100");
        }
    }
}
