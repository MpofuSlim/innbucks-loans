package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The endpoints' check of a caller's entitlement at a stage, read as configured now (FR-SSB-014). */
class WorkflowAccessTest {

    private WorkflowStageRepository repository;
    private WorkflowAccess access;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStageRepository.class);
        when(repository.findById("CREDIT_DECISION"))
                .thenReturn(Optional.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)));
        when(repository.findById("BOOKING")).thenReturn(Optional.empty());
        access = new WorkflowAccess(repository);
    }

    private static TestingAuthenticationToken caller(String... roles) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("someone", "n/a", roles);
        authentication.setAuthenticated(true);
        return authentication;
    }

    @Test
    @DisplayName("a caller's roles are read off their ROLE_ authorities and checked against the stage")
    void rolesFromAuthorities() {
        assertThat(access.may(caller("ROLE_CREDIT_MANAGER"), "CREDIT_DECISION", "WORK")).isTrue();
        assertThat(access.may(caller("ROLE_FINANCE"), "CREDIT_DECISION", "VIEW")).isFalse();
        assertThat(access.may(caller("ROLE_AGENTS", "SCOPE_read"), "CREDIT_DECISION", "VIEW")).isFalse();
        assertThat(access.may(caller("ROLE_SUPER_ADMIN"), "CREDIT_DECISION", "ASSIGN")).isTrue();
        assertThat(access.may(caller("ROLE_SOMETHING_ELSE"), "CREDIT_DECISION", "VIEW")).isFalse();
    }

    @Test
    @DisplayName("each check reads the stage afresh, so a change takes effect on the next request")
    void readAfresh() {
        access.may(caller("ROLE_CREDIT_MANAGER"), "CREDIT_DECISION", "WORK");
        access.may(caller("ROLE_CREDIT_MANAGER"), "CREDIT_DECISION", "WORK");

        verify(repository, times(2)).findById("CREDIT_DECISION");
    }

    @Test
    @DisplayName("an unknown stage is SUPER_ADMIN's alone, and an unauthenticated caller holds nothing")
    void unknownStageAndAnonymous() {
        assertThat(access.may(caller("ROLE_CREDIT_MANAGER"), "BOOKING", "VIEW")).isFalse();
        assertThat(access.may(caller("ROLE_SUPER_ADMIN"), "BOOKING", "VIEW")).isTrue();
        TestingAuthenticationToken anonymous = new TestingAuthenticationToken("someone", "n/a", "ROLE_SUPER_ADMIN");
        anonymous.setAuthenticated(false);
        assertThat(access.may(anonymous, "CREDIT_DECISION", "VIEW")).isFalse();
        assertThat(access.may(null, "CREDIT_DECISION", "VIEW")).isFalse();
        assertThat(access.may(Set.of(UserGroup.CREDIT_MANAGER), "CREDIT_DECISION", Entitlement.ASSIGN)).isTrue();
    }
}
