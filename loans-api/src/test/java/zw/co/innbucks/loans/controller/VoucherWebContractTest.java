package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.voucher.VoucherCodeResponse;
import zw.co.innbucks.loans.core.voucher.VoucherDeliveryStatus;
import zw.co.innbucks.loans.core.voucher.VoucherProduct;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionResult;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionService;
import zw.co.innbucks.loans.core.voucher.VoucherRefusal;
import zw.co.innbucks.loans.core.voucher.VoucherRefusedException;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VouchersUnavailableException;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.VoucherApiExamples;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The voucher endpoints (FR-SGL-033 to FR-SGL-040): who may read, reveal, cancel, resend and report; GetMore's validate
 * and redeem with every refusal by its own code; and the settlement CSV download.
 */
class VoucherWebContractTest {

    private static final VoucherResponse VOUCHER_8 = new VoucherResponse(8L, VoucherProduct.STAFF_GROCERY_LOAN,
            "SGL-2026-000151", "BRNET-20261003-0002", "E1001", "Nyasha Dube", "****4471", "**** **** **** 2151",
            new BigDecimal("250.00"), new BigDecimal("0.00"), new BigDecimal("250.00"), "USD",
            LocalDateTime.of(2026, 10, 3, 6, 5, 41), LocalDateTime.of(2026, 11, 2, 21, 59, 59), VoucherStatus.ISSUED,
            null, null, null, null, null, VoucherDeliveryStatus.PENDING, null, LocalDateTime.of(2026, 10, 4, 7, 2, 17));
    private static final VoucherRedemptionResult REDEEMED = new VoucherRedemptionResult("GM-POS-88412",
            "**** **** **** 8406", new BigDecimal("180.00"), new BigDecimal("120.00"), "USD",
            VoucherStatus.PARTIALLY_REDEEMED, "GM-AVD-01", LocalDateTime.of(2026, 10, 3, 15, 42, 10), false);

    private VoucherService vouchers;
    private VoucherSettlementService settlement;
    private VoucherRedemptionService redemptions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        vouchers = mock(VoucherService.class);
        settlement = mock(VoucherSettlementService.class);
        redemptions = mock(VoucherRedemptionService.class);
        mvc = MockMvcBuilders.standaloneSetup(secured(new VoucherController(vouchers, settlement)),
                        secured(new VoucherRedemptionController(redemptions)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Object secured(Object controller) {
        ProxyFactory proxy = new ProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(preAuthorize());
        return proxy.getProxy();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor as(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("preferred_username", "someone")
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
    @DisplayName("Credit, Finance, SUPER_ADMIN and VOUCHER_SUPPORT read vouchers; agents, HR and the till cannot")
    void readers() throws Exception {
        when(vouchers.vouchers(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(VOUCHER_8)));
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN", "VOUCHER_SUPPORT")) {
            mvc.perform(get("/lending/v1/vouchers").param("deliveryStatus", "FAILED").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].maskedCode").value("**** **** **** 2151"));
        }
        for (String role : List.of("AGENTS", "HUMAN_CAPITAL", "GETMORE")) {
            mvc.perform(get("/lending/v1/vouchers").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("only VOUCHER_SUPPORT sees a code in full, SUPER_ADMIN included, and only with a reason")
    void reveal() throws Exception {
        when(vouchers.reveal(7L, "Customer on the phone, cannot find the SMS")).thenReturn(
                new VoucherCodeResponse(7L, "4829 1506 7331 8406", "4829150673318406"));

        for (String role : List.of("SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE")) {
            mvc.perform(post("/lending/v1/vouchers/7/code-reveals").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REASON_REQUEST))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(vouchers);
        mvc.perform(post("/lending/v1/vouchers/7/code-reveals").with(as("VOUCHER_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("reason is required"));
        mvc.perform(post("/lending/v1/vouchers/7/code-reveals").with(as("VOUCHER_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REASON_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("4829 1506 7331 8406"))
                .andExpect(jsonPath("$.data.scanValue").value("4829150673318406"));
    }

    @Test
    @DisplayName("Credit and SUPER_ADMIN cancel; a spent voucher is a 409; support resends (202)")
    void cancelAndResend() throws Exception {
        when(vouchers.cancel(eq(8L), any())).thenReturn(VOUCHER_8);
        when(vouchers.cancel(eq(7L), any())).thenThrow(new ConflictException("Voucher 7 has been partly spent and"
                + " cannot be cancelled"));
        when(vouchers.resend(8L)).thenReturn(VOUCHER_8);

        mvc.perform(post("/lending/v1/vouchers/8/cancellation").with(as("VOUCHER_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.CANCEL_REQUEST))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/vouchers/8/cancellation").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.CANCEL_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Voucher 8 cancelled"));
        mvc.perform(post("/lending/v1/vouchers/7/cancellation").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.CANCEL_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mvc.perform(post("/lending/v1/vouchers/8/deliveries").with(as("FINANCE"))).andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/vouchers/8/deliveries").with(as("VOUCHER_SUPPORT")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").value("Voucher 8 queued to be sent again"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"));
    }

    @Test
    @DisplayName("the till redeems (201), a retry is answered as before (200), and only GETMORE may")
    void redeem() throws Exception {
        when(redemptions.redeem(any())).thenReturn(REDEEMED);

        for (String role : List.of("SUPER_ADMIN", "CREDIT_MANAGER", "VOUCHER_SUPPORT")) {
            mvc.perform(post("/lending/v1/voucher-redemptions").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REDEMPTION_REQUEST))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/lending/v1/voucher-redemptions").with(as("GETMORE"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REDEMPTION_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Redeemed USD 180.00; USD 120.00 left"))
                .andExpect(jsonPath("$.data.balanceAfter").value(120.00))
                .andExpect(jsonPath("$.data.replayed").value(false));

        when(redemptions.redeem(any())).thenReturn(new VoucherRedemptionResult("GM-POS-88412", "**** **** **** 8406",
                new BigDecimal("180.00"), new BigDecimal("120.00"), "USD", VoucherStatus.PARTIALLY_REDEEMED,
                "GM-AVD-01", LocalDateTime.of(2026, 10, 3, 15, 42, 10), true));
        mvc.perform(post("/lending/v1/voucher-redemptions").with(as("GETMORE"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REDEMPTION_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Already redeemed under reference GM-POS-88412; nothing more"
                        + " was spent"))
                .andExpect(jsonPath("$.data.replayed").value(true));
    }

    @Test
    @DisplayName("every refusal reaches the till as its own code and status; unconfigured keys are a 503")
    void refusals() throws Exception {
        for (VoucherRefusal refusal : VoucherRefusal.values()) {
            doThrow(new VoucherRefusedException(refusal)).when(redemptions).redeem(any());
            mvc.perform(post("/lending/v1/voucher-redemptions").with(as("GETMORE"))
                            .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REDEMPTION_REQUEST))
                    .andExpect(status().is(refusal.httpStatus()))
                    .andExpect(jsonPath("$.code").value(refusal.name()))
                    .andExpect(jsonPath("$.message").value(refusal.message()));
        }
        when(redemptions.validate(any())).thenThrow(new VouchersUnavailableException());
        mvc.perform(post("/lending/v1/voucher-validations").with(as("GETMORE"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.VALIDATION_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VOUCHERS_UNAVAILABLE"));
        mvc.perform(post("/lending/v1/voucher-redemptions").with(as("GETMORE"))
                        .contentType(MediaType.APPLICATION_JSON).content(VoucherApiExamples.REDEMPTION_REQUEST
                                .replace("180.00", "180.005")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.amount").value("amount must have at most 2 decimal places"));
    }

    @Test
    @DisplayName("the settlement report as JSON, or as a CSV download named for its day; Finance yes, support no")
    void settlementReport() throws Exception {
        when(settlement.csv(LocalDate.of(2026, 10, 3))).thenReturn("event,at\r\n");

        mvc.perform(get("/lending/v1/voucher-settlement-reports/2026-10-03").param("format", "csv")
                        .with(as("VOUCHER_SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/lending/v1/voucher-settlement-reports/2026-10-03").param("format", "csv")
                        .with(as("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"voucher-settlement-2026-10-03.csv\""))
                .andExpect(content().string("event,at\r\n"));
        mvc.perform(get("/lending/v1/voucher-settlement-reports/2026-10-03").param("format", "xml")
                        .with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("format must be json or csv"));
        mvc.perform(get("/lending/v1/voucher-settlement-reports/2026-10-03").with(as("CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }
}
