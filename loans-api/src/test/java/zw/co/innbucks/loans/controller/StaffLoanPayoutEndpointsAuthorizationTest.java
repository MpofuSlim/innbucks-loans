package zw.co.innbucks.loans.controller;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.InnbucksLoanInquiry;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoan;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDestination;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDispatcher;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMetrics;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutRequests;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.UnredeemedVoucherTreatment;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may act on a cash Staff Grocery Loan's payout: send one or every ready one (FINANCE, SUPER_ADMIN), ask the bank
 * about one (CREDIT_MANAGER too), record one paid on the bank's word (FINANCE, SUPER_ADMIN) or never booked
 * (SUPER_ADMIN alone); and nobody, whatever their role, on a loan they are a party to. Runs the REAL security chain,
 * exception handler, controller, payout requests and status service; only the repositories and the edges are mocked,
 * and a mocked {@link JwtDecoder} turns each bearer string into a token for one role.
 */
@SpringJUnitWebConfig(StaffLoanPayoutEndpointsAuthorizationTest.Config.class)
class StaffLoanPayoutEndpointsAuthorizationTest {

    private static final String LOANS = "/lending/v1/staff-loans";
    private static final String REFERENCE = "SGL-2027-000245";
    private static final Instant NOW = Instant.parse("2027-02-03T12:20:00Z");
    private static final List<String> ROLES = List.of("SUPER_ADMIN", "FINANCE", "CREDIT_MANAGER", "HUMAN_CAPITAL",
            "AGENTS");

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, StaffLoanController.class,
            StaffLoanPayoutRequests.class, StaffLoanPayoutStatusService.class})
    static class Config {

        @Bean
        MarketTimeZone marketTimeZone() {
            return new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        StaffLoanProperties staffLoanProperties() {
            return new StaffLoanProperties();
        }

        @Bean
        StaffLoanPayoutMetrics staffLoanPayoutMetrics() {
            return new StaffLoanPayoutMetrics(new SimpleMeterRegistry());
        }

        /** A transaction manager with no database behind it: the requests' own transaction begins and commits. */
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffLoanService loanService;
    @MockitoBean StaffLoanRepository loanRepository;
    @MockitoBean StaffMemberRepository memberRepository;
    @MockitoBean StaffNotificationService notificationService;
    @MockitoBean InnbucksLoanInquiry inquiry;
    @MockitoBean AuditService auditService;
    @MockitoBean AuthService authService;
    @MockitoBean StaffLoanPayoutDispatcher dispatcher;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    private final StaffMember chipo = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .nationalId("632223334C55").msisdn("263773456789").grade("C4")
            .employmentStatus(StaffEmploymentStatus.ACTIVE).engagementDate(LocalDate.of(2017, 3, 1))
            .walletAccountNumber("263773456789").build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        for (String role : ROLES) {
            when(jwtDecoder.decode(role + "-token")).thenReturn(Jwt.withTokenValue(role + "-token")
                    .header("alg", "HS256").subject(role).claim("preferred_username", role.toLowerCase())
                    .claim("realm_access", Map.of("roles", List.of(role)))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        }
        when(authService.getLoggedInUser()).thenReturn(user("finance1", "63-1111111-A-11", "0771000000"));
        when(authService.getLoggedInUsername()).thenReturn("finance1");
        when(loanRepository.lockById(anyLong())).thenReturn(Optional.empty());
        when(loanRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(loanRepository.findPayoutReadyIds(anyLong(), any(Pageable.class))).thenReturn(List.of());
        when(memberRepository.findById(2L)).thenReturn(Optional.of(chipo));
        when(dispatcher.requestSweep(any())).thenReturn(true);
        when(dispatcher.request(anyLong())).thenReturn(true);
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + role + "-token");
    }

    private static User user(String username, String idNumber, String mobile) {
        User user = new User();
        user.setUsername(username);
        user.setIdNumber(idNumber);
        user.setMobileNumber(mobile);
        return user;
    }

    private static StaffLoan cashLoan(StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().id(245L).reference(REFERENCE).staffMemberId(2L).offerId(58L)
                .employeeNumber("E1012").fullName("Chipo Banda").msisdn("263773456789").grade("C4")
                .amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2027, 2, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2027, 2, 2, 7, 12, 5))
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263773456789").payoutStatus(payout).build();
    }

    /** Each role is either let through to the service (which then finds no loan 245: 404) or refused at the door. */
    private void onlyFor(List<String> allowed, Supplier<MockHttpServletRequestBuilder> request, ResultMatcher through)
            throws Exception {
        for (String role : ROLES) {
            mvc.perform(as(role, request.get()))
                    .andExpect(allowed.contains(role) ? through : status().isForbidden());
        }
    }

    @Test
    @DisplayName("Pay out and Pay all ready: FINANCE and SUPER_ADMIN only")
    void sending() throws Exception {
        onlyFor(List.of("FINANCE", "SUPER_ADMIN"), () -> post(LOANS + "/245/payout"), status().isNotFound());
        onlyFor(List.of("FINANCE", "SUPER_ADMIN"), () -> post(LOANS + "/payouts"), status().isAccepted());
        verify(dispatcher, never()).request(anyLong());
    }

    @Test
    @DisplayName("status check: CREDIT_MANAGER, FINANCE and SUPER_ADMIN; not Human Capital")
    void checking() throws Exception {
        onlyFor(List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN"), () -> post(LOANS + "/245/payout/status-check"),
                status().isNotFound());
        verifyNoInteractions(inquiry);
    }

    @Test
    @DisplayName("paid: FINANCE and SUPER_ADMIN; never booked: SUPER_ADMIN alone")
    void recording() throws Exception {
        onlyFor(List.of("FINANCE", "SUPER_ADMIN"), () -> post(LOANS + "/245/payout/paid")
                .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.PAYOUT_PAID_REQUEST),
                status().isNotFound());
        onlyFor(List.of("SUPER_ADMIN"), () -> post(LOANS + "/245/payout/not-booked")
                .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_REQUEST),
                status().isNotFound());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("the borrower, as FINANCE, cannot send their own payout: 403, nothing changed or queued")
    void partyCannotSend() throws Exception {
        StaffLoan loan = cashLoan(StaffLoanPayoutStatus.REFUSED);
        when(loanRepository.lockById(245L)).thenReturn(Optional.of(loan));
        when(authService.getLoggedInUser()).thenReturn(user("cbanda", "63-2223334-C-55", null));

        mvc.perform(as("FINANCE", post(LOANS + "/245/payout")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("cbanda is a party to staff loan " + REFERENCE
                        + " and cannot send its payout; someone else must"));

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.REFUSED);
        verify(dispatcher, never()).request(anyLong());
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pay all ready leaves out the caller's own READY loans")
    void partyLeftOutOfTheSweep() throws Exception {
        StaffLoan loan = cashLoan(StaffLoanPayoutStatus.READY);
        when(loanRepository.findPayoutReadyIds(anyLong(), any(Pageable.class)))
                .thenAnswer(i -> (long) i.getArgument(0) < 245L ? List.of(245L) : List.of());
        when(loanRepository.findAllById(any())).thenReturn(List.of(loan));
        when(memberRepository.findAllById(any())).thenReturn(List.of(chipo));
        when(authService.getLoggedInUser()).thenReturn(user("cbanda", null, "+263 77 345 6789"));

        mvc.perform(as("FINANCE", post(LOANS + "/payouts")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.ready").value(0))
                .andExpect(jsonPath("$.data.skippedOwn").value(1));

        verify(dispatcher).requestSweep(Set.of(245L));
    }

    @Test
    @DisplayName("the borrower, as FINANCE, cannot record their own payout paid: 403, nothing recorded")
    void partyCannotConfirmPaid() throws Exception {
        StaffLoan loan = cashLoan(StaffLoanPayoutStatus.BOOKED);
        when(loanRepository.lockById(245L)).thenReturn(Optional.of(loan));
        when(authService.getLoggedInUser()).thenReturn(user("cbanda", null, "0773456789"));

        mvc.perform(as("FINANCE", post(LOANS + "/245/payout/paid")).contentType(MediaType.APPLICATION_JSON)
                        .content(StaffLoanApiExamples.PAYOUT_PAID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("cbanda is a party to staff loan " + REFERENCE
                        + " and cannot confirm its payout paid; someone else must"));

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("the borrower, as SUPER_ADMIN, cannot record their own payout never booked: 403, nothing recorded")
    void partyCannotConfirmNotBooked() throws Exception {
        StaffLoan loan = cashLoan(StaffLoanPayoutStatus.IN_DOUBT);
        ReflectionTestUtils.setField(loan, "payoutNotFoundAt",
                LocalDateTime.of(2027, 2, 2, 9, 44, 1));
        // Still reported missing two minutes ago: everything but who asks allows it.
        ReflectionTestUtils.setField(loan, "payoutCheckedAt", LocalDateTime.of(2027, 2, 3, 12, 18, 2));
        when(loanRepository.lockById(245L)).thenReturn(Optional.of(loan));
        when(authService.getLoggedInUser()).thenReturn(user("cbanda", "632223334C55", null));

        mvc.perform(as("SUPER_ADMIN", post(LOANS + "/245/payout/not-booked")).contentType(MediaType.APPLICATION_JSON)
                        .content(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("cbanda is a party to staff loan " + REFERENCE
                        + " and cannot record its payout as never booked; someone else must"));

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        verifyNoInteractions(notificationService);
    }
}
