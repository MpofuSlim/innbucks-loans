package zw.co.innbucks.loans.core.borrower;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.util.List;
import java.util.Optional;

/**
 * Signs a Staff Grocery Loan borrower in from the SuperApp (FR-SGL-025): trades the middleware's assertion that they
 * just authenticated for a short-lived loans session, for the staff member on the register with that phone.
 *
 * <p>The phone is the only thing taken from the assertion; who the borrower is comes from the staff register, never from
 * anything else the middleware put in it. A staff member who has left cannot sign in. Each assertion signs in once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BorrowerSessionService {

    static final String SIGNED_IN = "BORROWER_SIGNED_IN";
    static final String REFUSED = "BORROWER_SIGN_IN_REFUSED";

    private final MiddlewareAssertionVerifier verifier;
    private final AssertionUses assertionUses;
    private final StaffMemberRepository memberRepository;
    private final JwtService jwtService;
    private final BorrowerProperties properties;
    private final AuditService auditService;

    /**
     * @throws BorrowerSignInUnavailableException this server trusts no middleware key, so nothing can be verified
     * @throws AssertionRejectedException         the assertion is not genuine, current and unused (one opaque 401)
     * @throws NotOnStaffRegisterException        it is genuine, but its phone is not a current staff member's
     */
    @Transactional
    public BorrowerSession signIn(String assertion) {
        if (!verifier.isConfigured()) {
            throw new BorrowerSignInUnavailableException();
        }
        VerifiedAssertion verified;
        try {
            verified = verifier.verify(assertion);
        } catch (AssertionRejectedException rejected) {
            refused(null, "assertion rejected: " + rejected.getMessage());
            throw rejected;
        }
        StaffMember member = currentMemberFor(verified.phone()).orElse(null);
        if (member == null) {
            refused(MsisdnUtils.mask(verified.phone()), "not a current staff member's number");
            throw new NotOnStaffRegisterException();
        }
        try {
            assertionUses.spend(verified, BorrowerAssertionUse.Purpose.SIGN_IN, member.getId());
        } catch (AssertionRejectedException replay) {
            refused(MsisdnUtils.mask(verified.phone()), replay.getMessage());
            throw replay;
        }
        long ttl = properties.getSessionMinutes() * 60L;
        String token = jwtService.generateBorrowerToken(member.getId(), member.getEmployeeNumber(),
                member.getMsisdn(), verified.methods(), ttl);
        auditService.record(AuditLog.builder()
                .eventType(SIGNED_IN)
                .entityType("STAFF_MEMBER").entityId(String.valueOf(member.getId()))
                .actorId(JwtService.BORROWER_USERNAME_PREFIX + member.getEmployeeNumber()).channelUsed("superapp")
                .detail("methods:" + String.join(",", verified.methods()) + ";assertion:" + verified.jti()));
        log.info("Borrower {} signed in from the SuperApp ({})", member.getEmployeeNumber(),
                String.join(",", verified.methods()));
        return new BorrowerSession(token, "Bearer", ttl, member.getEmployeeNumber(), member.getFullName());
    }

    /** Who the signed-in borrower is. */
    @Transactional(readOnly = true)
    public BorrowerProfile profile(Long staffMemberId, List<String> methods) {
        StaffMember member = memberRepository.findById(staffMemberId).orElseThrow(NotOnStaffRegisterException::new);
        return new BorrowerProfile(member.getEmployeeNumber(), member.getFullName(),
                MsisdnUtils.mask(member.getMsisdn()), member.getDepartment(), methods);
    }

    /** The staff member on the register with this phone who has not left, written however the middleware wrote it. */
    Optional<StaffMember> currentMemberFor(String phone) {
        return BorrowerPhones.asRegistered(phone).flatMap(memberRepository::findByMsisdn)
                .filter(member -> !member.getEmploymentStatus().hasLeft());
    }

    private void refused(String phone, String reason) {
        log.warn("Borrower sign-in refused{}: {}", phone == null ? "" : " for " + phone, reason);
        auditService.record(AuditLog.builder()
                .eventType(REFUSED)
                .entityType("STAFF_MEMBER").entityId(phone)
                .actorId("superapp").channelUsed("superapp")
                .detail("reason:" + reason));
    }
}
