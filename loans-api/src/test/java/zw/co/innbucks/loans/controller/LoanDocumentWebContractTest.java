package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.document.AmendDocumentRequest;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentContent;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.user.FindUserServiceImpl;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The loan documents endpoints (FR-SSB-009): each read carries the caller's loan scope, the access log is
 * Credit's, and a replacement needs its content and reason. Runs behind production's {@code @PreAuthorize}
 * interceptor and read-scope resolver; no database, no Spring context.
 */
class LoanDocumentWebContractTest {

    private static final LoanDocumentSummary PAYSLIP_V2 = new LoanDocumentSummary(DocumentType.PAYSLIP, 2,
            DocumentOrigin.AMENDMENT, "application/pdf", 231402, "cd".repeat(32),
            "August payslip, as Credit asked; the June one was out of date", "tmoyo", null);

    private LoanDocumentService loanDocumentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        loanDocumentService = mock(LoanDocumentService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User agent = new User();
        agent.setId(7L);
        agent.setUsername("tmoyo");
        agent.setMerchant(Merchant.builder().merchantCode("harare-motors").build());
        when(userRepository.findByUsername("tmoyo")).thenReturn(Optional.of(agent));

        ProxyFactory secured = new ProxyFactory(new LoanDocumentController(loanDocumentService,
                new LoanReadScopeResolver(new FindUserServiceImpl(userRepository))));
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
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
    @DisplayName("an agent's reads carry their merchant-and-originator scope; Credit's are platform-wide")
    void readsCarryTheCallersScope() throws Exception {
        when(loanDocumentService.history(eq(42L), any())).thenReturn(List.of(PAYSLIP_V2));
        when(loanDocumentService.view(eq(42L), eq(DocumentType.PAYSLIP), any(), any())).thenReturn(
                new LoanDocumentContent(DocumentType.PAYSLIP, 2, DocumentOrigin.AMENDMENT, "application/pdf", 9,
                        "cd".repeat(32), "August payslip", "tmoyo", null, "JVBERi0xLjcK"));

        mvc.perform(get("/lending/v1/loans/42/documents").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentType").value("PAYSLIP"))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].origin").value("AMENDMENT"))
                .andExpect(jsonPath("$.data[0].content").doesNotExist());
        verify(loanDocumentService).history(42L, LoanReadScope.originator("harare-motors", 7L));

        mvc.perform(get("/lending/v1/loans/42/documents/PAYSLIP").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("JVBERi0xLjcK"))
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"));
        verify(loanDocumentService).view(42L, DocumentType.PAYSLIP, null, LoanReadScope.platform());

        mvc.perform(get("/lending/v1/loans/42/documents/PAYSLIP/versions/1").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk());
        verify(loanDocumentService).view(42L, DocumentType.PAYSLIP, 1, LoanReadScope.platform());
    }

    @Test
    @DisplayName("a path that names no document type is a 400, not a lookup")
    void unknownDocumentType() throws Exception {
        mvc.perform(get("/lending/v1/loans/42/documents/PASSPORT").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'documentType'"));
        verifyNoInteractions(loanDocumentService);
    }

    @Test
    @DisplayName("a replacement needs content and a reason, reported together, before the service is called")
    void replacementNeedsContentAndReason() throws Exception {
        mvc.perform(put("/lending/v1/loans/42/documents/PAYSLIP").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.content").value("Content is required"))
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));
        verifyNoInteractions(loanDocumentService);
    }

    @Test
    @DisplayName("a replacement answers with the new version and says which is now current")
    void replacementAnswersWithTheNewVersion() throws Exception {
        when(loanDocumentService.amend(eq(42L), eq(DocumentType.PAYSLIP), any(), any())).thenReturn(PAYSLIP_V2);

        mvc.perform(put("/lending/v1/loans/42/documents/PAYSLIP").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"JVBERi0xLjcK\",\"reason\":\"August payslip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PAYSLIP replaced; version 2 is now current"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.content").doesNotExist());
        ArgumentCaptor<AmendDocumentRequest> request = ArgumentCaptor.forClass(AmendDocumentRequest.class);
        verify(loanDocumentService).amend(eq(42L), eq(DocumentType.PAYSLIP), request.capture(),
                eq(LoanReadScope.originator("harare-motors", 7L)));
        assertThat(request.getValue().getContent()).isEqualTo("JVBERi0xLjcK");
        assertThat(request.getValue().getReason()).isEqualTo("August payslip");
    }

    @Test
    @DisplayName("an unacceptable file is a 400 INVALID_DOCUMENT and a closed loan a 409, each with its message")
    void refusalsCarryTheirMessage() throws Exception {
        when(loanDocumentService.amend(eq(42L), eq(DocumentType.NATIONAL_ID), any(), any()))
                .thenThrow(new FileSignatureValidator.UnsafeFileException(
                        "content is not a recognised document type (PDF/PNG/JPEG/GIF)"));
        when(loanDocumentService.amend(eq(43L), eq(DocumentType.PAYSLIP), any(), any()))
                .thenThrow(new ConflictException("Documents of loan 000000043 can no longer be replaced"
                        + " (SSB status APPROVED, credit status APPROVED)"));
        String body = "{\"content\":\"AAECAwQ=\",\"reason\":\"Clearer copy\"}";

        mvc.perform(put("/lending/v1/loans/42/documents/NATIONAL_ID").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"))
                .andExpect(jsonPath("$.message").value("content is not a recognised document type (PDF/PNG/JPEG/GIF)"));
        mvc.perform(put("/lending/v1/loans/43/documents/PAYSLIP").with(as("tmoyo", "AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("the access log is Credit's: an agent is refused (403) and the service never called")
    void accessLogIsCredits() throws Exception {
        mvc.perform(get("/lending/v1/loans/42/documents/access-log").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(loanDocumentService);

        when(loanDocumentService.accessLog(42L)).thenReturn(List.of());
        mvc.perform(get("/lending/v1/loans/42/documents/access-log").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(loanDocumentService).accessLog(42L);
    }
}
