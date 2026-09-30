package zw.co.innbucks.loans.core.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a caller holds an entitlement at a stage, as configured now (FR-SSB-014). Read on every call, so a change
 * takes effect on the next request. Used by the endpoints' {@code @PreAuthorize} as
 * {@code isAuthenticated() and @workflowAccess.may(authentication, 'CREDIT_DECISION', 'WORK')}.
 */
@Component("workflowAccess")
@RequiredArgsConstructor
public class WorkflowAccess {

    private static final String ROLE_PREFIX = "ROLE_";

    private final WorkflowStageRepository workflowStageRepository;

    @Transactional(readOnly = true)
    public boolean may(Authentication authentication, String stageCode, String entitlement) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return may(groupsOf(authentication), stageCode, Entitlement.valueOf(entitlement));
    }

    /**
     * Whether someone with these roles holds the entitlement at the stage; an unknown stage grants SUPER_ADMIN only.
     */
    @Transactional(readOnly = true)
    public boolean may(Collection<UserGroup> groups, String stageCode, Entitlement entitlement) {
        return workflowStageRepository.findById(stageCode)
                .map(stage -> stage.grants(groups, entitlement))
                .orElse(groups.contains(UserGroup.SUPER_ADMIN));
    }

    static Set<UserGroup> groupsOf(Authentication authentication) {
        Set<UserGroup> groups = EnumSet.noneOf(UserGroup.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith(ROLE_PREFIX)) {
                String role = name.substring(ROLE_PREFIX.length());
                Arrays.stream(UserGroup.values()).filter(group -> group.name().equals(role)).forEach(groups::add);
            }
        }
        return groups;
    }
}
