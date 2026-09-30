package zw.co.innbucks.loans.core.authority;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Credit approval limits and referral (FR-PBL-028, which FR-SSB-015 applies to SSB loans). An administrator sets up
 * authority levels, each with the largest principal it may approve, and gives each credit officer a level. Once any
 * level exists, an officer approves only loans their level covers and refers the rest to a higher level; an officer
 * with no level approves nothing. SUPER_ADMIN may approve any amount, so no configuration can lock the platform out of
 * approving. While no level exists nothing is limited, as before limits existed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAuthorityService {

    static final String LEVEL_CREATED = "CREDIT_AUTHORITY_LEVEL_CREATED";
    static final String LEVEL_CHANGED = "CREDIT_AUTHORITY_LEVEL_CHANGED";
    static final String LEVEL_DELETED = "CREDIT_AUTHORITY_LEVEL_DELETED";
    static final String USER_LEVEL_CHANGED = "USER_CREDIT_AUTHORITY_CHANGED";
    private static final String CHANNEL = "admin-portal";

    private final CreditAuthorityLevelRepository levelRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /** Every level, lowest limit first, with who holds it. */
    @Transactional(readOnly = true)
    public List<CreditAuthorityLevelResponse> levels() {
        Map<String, TreeSet<String>> holders = new TreeMap<>();
        for (User user : userRepository.findByCreditAuthorityLevelIsNotNull()) {
            holders.computeIfAbsent(user.getCreditAuthorityLevel(), code -> new TreeSet<>()).add(user.getUsername());
        }
        return levelRepository.findAllRanked().stream()
                .map(level -> CreditAuthorityLevelResponse.of(level,
                        List.copyOf(holders.getOrDefault(level.getCode(), new TreeSet<>()))))
                .toList();
    }

    /**
     * Adds a level. The first level added puts limits on every approval from then on.
     *
     * @throws ConflictException a level with the code, or with the same limit, already exists
     */
    @Transactional
    public CreditAuthorityLevelResponse create(CreateCreditAuthorityLevelRequest request) {
        String code = request.getCode().trim();
        if (levelRepository.existsById(code)) {
            throw new ConflictException("Credit authority level " + code + " already exists");
        }
        BigDecimal maximum = money(request.getMaximumPrincipal());
        requireLimitFree(maximum, code);
        String username = authService.getLoggedInUsername();
        CreditAuthorityLevel level = levelRepository.save(CreditAuthorityLevel.builder()
                .code(code)
                .name(request.getName().trim())
                .maximumPrincipal(maximum)
                .updatedBy(username)
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
        log.info("Credit authority level {} created by {}: {}", code, username, describe(level));
        audit(LEVEL_CREATED, "CREDIT_AUTHORITY_LEVEL", code, username, "created=" + describe(level));
        return CreditAuthorityLevelResponse.of(level, List.of());
    }

    /**
     * Replaces a level's name and limit. Applies to the next approval by anyone who holds it.
     *
     * @throws NotFoundException no such level
     * @throws ConflictException another level has the same limit
     */
    @Transactional
    public CreditAuthorityLevelResponse update(String code, UpdateCreditAuthorityLevelRequest request) {
        CreditAuthorityLevel level = level(code);
        BigDecimal maximum = money(request.getMaximumPrincipal());
        requireLimitFree(maximum, code);
        String before = describe(level);
        String username = authService.getLoggedInUsername();
        level.setName(request.getName().trim());
        level.setMaximumPrincipal(maximum);
        level.setUpdatedBy(username);
        level.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        CreditAuthorityLevel saved = levelRepository.save(level);
        String after = describe(saved);
        log.info("Credit authority level {} changed by {}: {} -> {}", code, username, before, after);
        audit(LEVEL_CHANGED, "CREDIT_AUTHORITY_LEVEL", code, username, "before=" + before + " after=" + after);
        return CreditAuthorityLevelResponse.of(saved, holdersOf(code));
    }

    /**
     * Removes a level nobody holds. Removing the last level lifts every limit.
     *
     * @throws NotFoundException no such level
     * @throws ConflictException someone holds it
     */
    @Transactional
    public void delete(String code) {
        CreditAuthorityLevel level = level(code);
        List<String> holders = holdersOf(code);
        if (!holders.isEmpty()) {
            throw new ConflictException(String.format("%s is held by %s; give them another level first",
                    level.getName(), String.join(", ", holders)));
        }
        String username = authService.getLoggedInUsername();
        levelRepository.delete(level);
        log.info("Credit authority level {} deleted by {}: {}", code, username, describe(level));
        audit(LEVEL_DELETED, "CREDIT_AUTHORITY_LEVEL", code, username, "deleted=" + describe(level));
    }

    /**
     * Gives the user a level, or takes theirs away when {@code code} is blank.
     *
     * @throws NotFoundException        no such user
     * @throws IllegalArgumentException no such level, or the user is an agent or SUPER_ADMIN
     */
    @Transactional
    public UserResponse assign(Long userId, String code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        String wanted = StringUtils.trimToNull(code);
        if (wanted != null) {
            if (levelRepository.findById(wanted).isEmpty()) {
                throw new IllegalArgumentException("Unknown credit authority level " + wanted);
            }
            if (user.getGroups().contains(UserGroup.AGENTS)) {
                throw new IllegalArgumentException(user.getUsername()
                        + " is an agent; agents originate applications and never approve them");
            }
            if (user.getGroups().contains(UserGroup.SUPER_ADMIN)) {
                throw new IllegalArgumentException(user.getUsername()
                        + " is SUPER_ADMIN, who may approve any amount; a credit authority level would not limit them");
            }
        }
        String before = user.getCreditAuthorityLevel();
        if (!Objects.equals(before, wanted)) {
            user.setCreditAuthorityLevel(wanted);
            user = userRepository.save(user);
            String username = authService.getLoggedInUsername();
            log.info("Credit authority of {} changed by {}: {} -> {}", user.getUsername(), username,
                    before == null ? "none" : before, wanted == null ? "none" : wanted);
            audit(USER_LEVEL_CHANGED, "USER", String.valueOf(user.getId()), username,
                    "user=" + user.getUsername() + " before=" + (before == null ? "none" : before)
                            + " after=" + (wanted == null ? "none" : wanted));
        }
        return UserResponse.from(user);
    }

    /** What the officer's authority is over the principal. */
    @Transactional(readOnly = true)
    public CreditAuthorityAssessment assess(BigDecimal principal, User officer) {
        List<CreditAuthorityLevel> levels = levelRepository.findAllRanked();
        if (levels.isEmpty()) {
            return CreditAuthorityAssessment.unlimited();
        }
        Optional<CreditAuthorityLevel> required = levels.stream().filter(level -> level.covers(principal)).findFirst();
        Optional<CreditAuthorityLevel> own = Optional.ofNullable(officer == null ? null
                        : officer.getCreditAuthorityLevel())
                .flatMap(code -> levels.stream().filter(level -> level.getCode().equals(code)).findFirst());
        boolean superAdmin = officer != null && officer.getGroups() != null
                && officer.getGroups().contains(UserGroup.SUPER_ADMIN);
        return new CreditAuthorityAssessment(true,
                required.map(CreditAuthorityLevelSummary::of).orElse(null),
                required.isEmpty(),
                own.map(CreditAuthorityLevelSummary::of).orElse(null),
                superAdmin || own.map(level -> level.covers(principal)).orElse(false));
    }

    /**
     * Refuses an approval above the approver's authority, naming who may give it.
     *
     * @throws AccessDeniedException the approver's level does not cover the loan's principal, or they have none
     */
    @Transactional(readOnly = true)
    public void requireWithinLimit(Loan loan, User approver) {
        CreditAuthorityAssessment assessment = assess(loan.getPrincipal(), approver);
        if (assessment.withinYourLimit()) {
            return;
        }
        String username = approver == null ? null : approver.getUsername();
        if (assessment.yourLevel() == null) {
            throw new AccessDeniedException(String.format(
                    "%s has no credit approval limit, so cannot approve loan %s; refer it to %s", username,
                    loan.getReference(), assessment.approversDescription()));
        }
        throw new AccessDeniedException(String.format(
                "Loan %s is for %s, above %s's approval limit of %s (%s); refer it to %s", loan.getReference(),
                amount(loan.getPrincipal()), username, amount(assessment.yourLevel().maximumPrincipal()),
                assessment.yourLevel().name(), assessment.approversDescription()));
    }

    /**
     * Emails whoever may approve a referred loan: everyone at a level that covers it, or SUPER_ADMIN when it is above
     * every level. Loan references only, never the applicant. Best effort: a referral stands if the email fails.
     */
    public void notifyReferral(Loan loan, String referredBy, CreditAuthorityAssessment assessment,
                               InternalApprovalStatus recommendation) {
        try {
            List<String> recipients = approversOf(loan.getPrincipal(), assessment).stream()
                    .filter(user -> !StringUtils.equalsIgnoreCase(user.getUsername(), referredBy))
                    .map(User::getEmail)
                    .filter(StringUtils::isNotBlank)
                    .map(email -> email.trim().toLowerCase())
                    .distinct()
                    .toList();
            if (recipients.isEmpty()) {
                log.warn("Loan {} referred to {}, but nobody who may approve it has an email address",
                        loan.getReference(), assessment.referredTo());
                return;
            }
            String subject = "Referred to you: credit decision, loan " + loan.getReference();
            String body = String.format("%s referred loan %s, for %s, recommending %s. It is above their approval"
                            + " limit and needs %s.%n%nIt is in the credit decision queue.", referredBy,
                    loan.getReference(), amount(loan.getPrincipal()),
                    recommendation == InternalApprovalStatus.APPROVED ? "approval" : "rejection",
                    assessment.approversDescription());
            recipients.forEach(recipient -> notificationService.sendEmail(recipient, subject, body));
        } catch (RuntimeException ex) {
            log.error("Referral email for loan {} could not be queued", loan.getReference(), ex);
        }
    }

    /** Everyone whose authority covers the principal. */
    private List<User> approversOf(BigDecimal principal, CreditAuthorityAssessment assessment) {
        if (assessment.aboveEveryLevel()) {
            return userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN);
        }
        Collection<String> covering = levelRepository.findAllRanked().stream()
                .filter(level -> level.covers(principal))
                .map(CreditAuthorityLevel::getCode)
                .toList();
        return userRepository.findByCreditAuthorityLevelIn(covering);
    }

    private CreditAuthorityLevel level(String code) {
        return levelRepository.findById(code)
                .orElseThrow(() -> new NotFoundException("No credit authority level " + code));
    }

    private List<String> holdersOf(String code) {
        return userRepository.findByCreditAuthorityLevelIsNotNull().stream()
                .filter(user -> code.equals(user.getCreditAuthorityLevel()))
                .map(User::getUsername)
                .sorted()
                .toList();
    }

    /** Levels rank by their limit, so two cannot share one, and only one may have none. */
    private void requireLimitFree(BigDecimal maximum, String code) {
        Optional<CreditAuthorityLevel> taken = maximum == null ? levelRepository.findByMaximumPrincipalIsNull()
                : levelRepository.findByMaximumPrincipal(maximum);
        taken.filter(other -> !other.getCode().equals(code)).ifPresent(other -> {
            throw new ConflictException(maximum == null
                    ? String.format("%s already approves any amount; only one level can have no limit", other.getName())
                    : String.format("%s already has a limit of %s; each level needs its own", other.getName(),
                    maximum.toPlainString()));
        });
    }

    /** Money to the cent, as the column holds it. */
    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String amount(BigDecimal value) {
        return value == null ? "an unknown amount" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String describe(CreditAuthorityLevel level) {
        return "name:" + level.getName() + ";maximumPrincipal:"
                + (level.getMaximumPrincipal() == null ? "any" : level.getMaximumPrincipal().toPlainString());
    }

    private void audit(String eventType, String entityType, String entityId, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(entityType).entityId(entityId)
                .actorId(actor).channelUsed(CHANNEL)
                .detail(detail));
    }
}
