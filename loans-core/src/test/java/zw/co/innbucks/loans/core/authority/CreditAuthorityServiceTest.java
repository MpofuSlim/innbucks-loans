package zw.co.innbucks.loans.core.authority;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Setting up credit approval limits and giving them to users (FR-PBL-028), and what they allow. */
class CreditAuthorityServiceTest {

    private final List<CreditAuthorityLevel> stored = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private CreditAuthorityLevelRepository levelRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private CreditAuthorityService service;

    @BeforeEach
    void setUp() {
        levelRepository = mock(CreditAuthorityLevelRepository.class);
        when(levelRepository.findAllRanked()).thenAnswer(i -> stored.stream()
                .sorted((a, b) -> a.getMaximumPrincipal() == null ? 1 : b.getMaximumPrincipal() == null ? -1
                        : a.getMaximumPrincipal().compareTo(b.getMaximumPrincipal()))
                .toList());
        when(levelRepository.findById(any())).thenAnswer(i -> stored.stream()
                .filter(level -> level.getCode().equals(i.getArgument(0))).findFirst());
        when(levelRepository.existsById(any())).thenAnswer(i -> stored.stream()
                .anyMatch(level -> level.getCode().equals(i.getArgument(0))));
        when(levelRepository.findByMaximumPrincipal(any())).thenAnswer(i -> stored.stream()
                .filter(level -> level.getMaximumPrincipal() != null
                        && level.getMaximumPrincipal().compareTo(i.getArgument(0)) == 0).findFirst());
        when(levelRepository.findByMaximumPrincipalIsNull()).thenAnswer(i -> stored.stream()
                .filter(level -> level.getMaximumPrincipal() == null).findFirst());
        when(levelRepository.save(any())).thenAnswer(i -> {
            CreditAuthorityLevel level = i.getArgument(0);
            stored.removeIf(other -> other.getCode().equals(level.getCode()));
            stored.add(level);
            return level;
        });
        userRepository = mock(UserRepository.class);
        when(userRepository.findByCreditAuthorityLevelIsNotNull()).thenAnswer(i -> users.stream()
                .filter(user -> user.getCreditAuthorityLevel() != null).toList());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        auditService = mock(AuditService.class);
        service = new CreditAuthorityService(levelRepository, userRepository, authService, auditService,
                mock(NotificationService.class));
    }

    private static CreateCreditAuthorityLevelRequest create(String code, String name, String maximum) {
        return CreateCreditAuthorityLevelRequest.builder().code(code).name(name)
                .maximumPrincipal(maximum == null ? null : new BigDecimal(maximum)).build();
    }

    private User user(long id, String username, String level, UserGroup... groups) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setCreditAuthorityLevel(level);
        user.setGroups(Set.of(groups));
        users.add(user);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private List<AuditLog> audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    @Test
    @DisplayName("a level is added to the cent, audited, and listed lowest limit first with who holds it")
    void levelsAreAddedAndListed() {
        service.create(create("HEAD_OF_CREDIT", " Head of Credit ", null));
        CreditAuthorityLevelResponse senior = service.create(create("SENIOR_CREDIT_OFFICER", "Senior credit officer",
                "2500"));
        service.create(create("CREDIT_OFFICER", "Credit officer", "1000.5"));
        user(3, "rnyathi", "SENIOR_CREDIT_OFFICER", UserGroup.CREDIT_MANAGER);
        user(4, "cmanager", "SENIOR_CREDIT_OFFICER", UserGroup.CREDIT_MANAGER);

        assertThat(senior.maximumPrincipal()).isEqualByComparingTo("2500").hasToString("2500.00");
        assertThat(senior.updatedBy()).isEqualTo("admin");
        assertThat(service.levels()).extracting(CreditAuthorityLevelResponse::code)
                .containsExactly("CREDIT_OFFICER", "SENIOR_CREDIT_OFFICER", "HEAD_OF_CREDIT");
        assertThat(service.levels().get(1).holders()).containsExactly("cmanager", "rnyathi");
        assertThat(service.levels().get(2).name()).isEqualTo("Head of Credit");
        assertThat(audited()).extracting(AuditLog::getDetail).contains(
                "created=name:Senior credit officer;maximumPrincipal:2500.00",
                "created=name:Head of Credit;maximumPrincipal:any");
    }

    @Test
    @DisplayName("no two levels share a code or a limit, and only one has no limit (409)")
    void levelsAreDistinct() {
        service.create(create("CREDIT_OFFICER", "Credit officer", "1000"));
        service.create(create("HEAD_OF_CREDIT", "Head of Credit", null));

        assertThatThrownBy(() -> service.create(create("CREDIT_OFFICER", "Again", "500")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Credit authority level CREDIT_OFFICER already exists");
        assertThatThrownBy(() -> service.create(create("JUNIOR", "Junior", "1000.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Credit officer already has a limit of 1000.00; each level needs its own");
        assertThatThrownBy(() -> service.create(create("BOARD", "Board", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Head of Credit already approves any amount; only one level can have no limit");
        // Its own limit is not a clash when it is changed.
        assertThat(service.update("CREDIT_OFFICER", new UpdateCreditAuthorityLevelRequest("Officer",
                new BigDecimal("1000"))).name()).isEqualTo("Officer");
    }

    @Test
    @DisplayName("a change is audited with what it was; an unknown level is a 404")
    void levelIsChanged() {
        service.create(create("SENIOR_CREDIT_OFFICER", "Senior credit officer", "2500"));
        user(3, "rnyathi", "SENIOR_CREDIT_OFFICER", UserGroup.CREDIT_MANAGER);

        CreditAuthorityLevelResponse changed = service.update("SENIOR_CREDIT_OFFICER",
                new UpdateCreditAuthorityLevelRequest("Senior credit officer", new BigDecimal("3000")));

        assertThat(changed.maximumPrincipal()).hasToString("3000.00");
        assertThat(changed.holders()).containsExactly("rnyathi");
        assertThat(audited()).extracting(AuditLog::getDetail).contains(
                "before=name:Senior credit officer;maximumPrincipal:2500.00"
                        + " after=name:Senior credit officer;maximumPrincipal:3000.00");
        assertThatThrownBy(() -> service.update("JUNIOR", new UpdateCreditAuthorityLevelRequest("Junior", null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No credit authority level JUNIOR");
    }

    @Test
    @DisplayName("a level someone holds cannot be removed; one nobody holds can, audited")
    void levelIsRemovedOnlyWhenUnheld() {
        service.create(create("SENIOR_CREDIT_OFFICER", "Senior credit officer", "2500"));
        User rnyathi = user(3, "rnyathi", "SENIOR_CREDIT_OFFICER", UserGroup.CREDIT_MANAGER);

        assertThatThrownBy(() -> service.delete("SENIOR_CREDIT_OFFICER"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Senior credit officer is held by rnyathi; give them another level first");
        verify(levelRepository, never()).delete(any());

        rnyathi.setCreditAuthorityLevel(null);
        service.delete("SENIOR_CREDIT_OFFICER");
        verify(levelRepository).delete(any());
        assertThat(audited()).extracting(AuditLog::getEventType).contains("CREDIT_AUTHORITY_LEVEL_DELETED");
    }

    @Test
    @DisplayName("a user is given a level, or has theirs taken away, audited; never an agent or SUPER_ADMIN, or a"
            + " level that does not exist")
    void userIsGivenALevel() {
        service.create(create("SENIOR_CREDIT_OFFICER", "Senior credit officer", "2500"));
        user(3, "rnyathi", null, UserGroup.CREDIT_MANAGER);
        user(7, "tmoyo", null, UserGroup.AGENTS);
        user(1, "admin", null, UserGroup.SUPER_ADMIN);

        UserResponse given = service.assign(3L, " SENIOR_CREDIT_OFFICER ");
        assertThat(given.creditAuthorityLevel()).isEqualTo("SENIOR_CREDIT_OFFICER");
        assertThat(service.assign(3L, null).creditAuthorityLevel()).isNull();
        assertThat(audited()).extracting(AuditLog::getDetail).containsExactly(
                "created=name:Senior credit officer;maximumPrincipal:2500.00",
                "user=rnyathi before=none after=SENIOR_CREDIT_OFFICER",
                "user=rnyathi before=SENIOR_CREDIT_OFFICER after=none");

        assertThatThrownBy(() -> service.assign(3L, "JUNIOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown credit authority level JUNIOR");
        assertThatThrownBy(() -> service.assign(7L, "SENIOR_CREDIT_OFFICER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tmoyo is an agent; agents originate applications and never approve them");
        assertThatThrownBy(() -> service.assign(1L, "SENIOR_CREDIT_OFFICER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("admin is SUPER_ADMIN, who may approve any amount; a credit authority level would not"
                        + " limit them");
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(99L, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User 99 not found");
    }

    @Test
    @DisplayName("the assessment: unlimited with no levels; the lowest covering level, the officer's own, and"
            + " whether it covers the amount, to the cent")
    void assessment() {
        User officer = user(4, "cmanager", "CREDIT_OFFICER", UserGroup.CREDIT_MANAGER);
        assertThat(service.assess(new BigDecimal("999999"), officer)).isEqualTo(CreditAuthorityAssessment.unlimited());

        service.create(create("CREDIT_OFFICER", "Credit officer", "1000"));
        service.create(create("SENIOR_CREDIT_OFFICER", "Senior credit officer", "3000"));

        CreditAuthorityAssessment atLimit = service.assess(new BigDecimal("1000.00"), officer);
        assertThat(atLimit.withinYourLimit()).isTrue();
        assertThat(atLimit.requiredLevel().code()).isEqualTo("CREDIT_OFFICER");

        CreditAuthorityAssessment justAbove = service.assess(new BigDecimal("1000.01"), officer);
        assertThat(justAbove.withinYourLimit()).isFalse();
        assertThat(justAbove.requiredLevel().code()).isEqualTo("SENIOR_CREDIT_OFFICER");
        assertThat(justAbove.yourLevel().code()).isEqualTo("CREDIT_OFFICER");
        assertThat(justAbove.referredTo()).isEqualTo("SENIOR_CREDIT_OFFICER");
        assertThat(justAbove.approversDescription()).isEqualTo("Senior credit officer or above");

        CreditAuthorityAssessment aboveAll = service.assess(new BigDecimal("3000.01"), officer);
        assertThat(aboveAll.aboveEveryLevel()).isTrue();
        assertThat(aboveAll.requiredLevel()).isNull();
        assertThat(aboveAll.referredTo()).isEqualTo("SUPER_ADMIN");

        User admin = user(1, "admin", null, UserGroup.SUPER_ADMIN);
        assertThat(service.assess(new BigDecimal("3000.01"), admin).withinYourLimit()).isTrue();
        User unlevelled = user(5, "gmoyo", null, UserGroup.CREDIT_MANAGER);
        assertThat(service.assess(new BigDecimal("10"), unlevelled).withinYourLimit()).isFalse();
        // An unknown principal is covered only by a level with no limit.
        assertThat(service.assess(null, officer).withinYourLimit()).isFalse();
    }
}
