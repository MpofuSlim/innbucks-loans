package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.instrument.InstrumentPreview;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateResponse;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateService;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.PublishInstrumentTemplateRequest;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.user.FindUserServiceImpl;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The instrument endpoints (FR-SSB-013): only SUPER_ADMIN publishes wording, a preview needs the terms and
 * identity, and a loan's signed instruments are read in the caller's loan scope. Runs behind production's
 * {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class InstrumentWebContractTest {

    private static final String PUBLISH = """
            {"instrumentType": "LOAN_AGREEMENT", "title": "SSB Loan Agreement", "body": "{{applicantName}} borrows."}""";

    private InstrumentTemplateService templateService;
    private LoanService loanService;
    private SignedInstrumentService signedInstrumentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        templateService = mock(InstrumentTemplateService.class);
        loanService = mock(LoanService.class);
        signedInstrumentService = mock(SignedInstrumentService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User agent = new User();
        agent.setId(7L);
        agent.setUsername("tmoyo");
        agent.setMerchant(Merchant.builder().merchantCode("harare-motors").build());
        when(userRepository.findByUsername("tmoyo")).thenReturn(Optional.of(agent));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(secured(new InstrumentTemplateController(templateService)),
                        secured(new SignedInstrumentController(loanService, signedInstrumentService,
                                new LoanReadScopeResolver(new FindUserServiceImpl(userRepository)))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return secured.getProxy();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor as(String username, String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) new RolesJwtAuthenticationConverter().convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    @Test
    @DisplayName("only SUPER_ADMIN publishes wording: anyone else is refused (403) and nothing is published")
    void onlySuperAdminPublishes() throws Exception {
        when(templateService.publish(any())).thenReturn(new InstrumentTemplateResponse(InstrumentType.LOAN_AGREEMENT,
                3, "SSB Loan Agreement", "{{applicantName}} borrows.", "admin", null));

        mvc.perform(post("/lending/v1/instrument-templates").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(PUBLISH))
                .andExpect(status().isForbidden());
        verifyNoInteractions(templateService);

        mvc.perform(post("/lending/v1/instrument-templates").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(PUBLISH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("LOAN_AGREEMENT version 3 published and in force"))
                .andExpect(jsonPath("$.data.version").value(3));
        verify(templateService).publish(new PublishInstrumentTemplateRequest(InstrumentType.LOAN_AGREEMENT,
                "SSB Loan Agreement", "{{applicantName}} borrows."));
    }

    @Test
    @DisplayName("publishing needs a type, a title and a body, all reported in one 400")
    void publishNeedsEveryField() throws Exception {
        mvc.perform(post("/lending/v1/instrument-templates").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.instrumentType").value("Instrument type is required"))
                .andExpect(jsonPath("$.data.title").value("Title is required"))
                .andExpect(jsonPath("$.data.body").value("Body is required"));
        verifyNoInteractions(templateService);
    }

    @Test
    @DisplayName("the placeholders are listed for anyone signed in; a path naming no instrument type is a 400")
    void placeholdersAndTypes() throws Exception {
        mvc.perform(get("/lending/v1/instrument-templates/placeholders").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthlyDeduction").value("Monthly amount SSB is instructed to deduct"));
        mvc.perform(get("/lending/v1/instrument-templates/SELFIE/versions").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'instrumentType'"));
    }

    @Test
    @DisplayName("a preview needs the terms and identity, each missing one listed; complete, it is the loan service's")
    void preview() throws Exception {
        mvc.perform(post("/lending/v1/loans/instruments/preview").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenor\": 6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.amount").value("Loan amount is required"))
                .andExpect(jsonPath("$.data.ecNumber").value("EC number is required"));
        verifyNoInteractions(loanService);

        when(loanService.previewInstruments(any())).thenReturn(List.of(new InstrumentPreview(
                InstrumentType.SSB_DEDUCTION_AUTHORITY, 2, "SSB Deduction Authority", "Deduct USD 69.03.", "ab")));
        mvc.perform(post("/lending/v1/loans/instruments/preview").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"amount": 300, "tenor": 6, "ecNumber": "7654321B", "nationalIdNumber": "63-7654321-B-42",
                                 "mobileNumber": "0772345678", "dateOfBirth": "1990-06-18"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].content").value("Deduct USD 69.03."));
    }

    @Test
    @DisplayName("a loan's signed instruments are read in the caller's scope: an agent's own, Credit's all")
    void signedInstrumentsCarryTheScope() throws Exception {
        when(signedInstrumentService.forLoan(any(), any())).thenReturn(List.of());

        mvc.perform(get("/lending/v1/loans/43/signed-instruments").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(signedInstrumentService).forLoan(43L, LoanReadScope.originator("harare-motors", 7L));

        mvc.perform(get("/lending/v1/loans/43/signed-instruments").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk());
        verify(signedInstrumentService).forLoan(43L, LoanReadScope.platform());
    }
}
