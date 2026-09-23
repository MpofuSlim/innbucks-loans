package zw.co.reikan.loans.core.merchant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.api.MerchantDto;
import zw.co.reikan.loans.core.api.UpdateMerchantRequest;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A merchant's disbursement type + account decide where every approved loan of
 * that merchant is paid, so a change must leave an attributable, masked trail and
 * the account must not be readable in full by non-admins.
 */
class MerchantServiceTest {

    private MerchantRepository merchantRepository;
    private AuditService auditService;
    private MerchantService service;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        auditService = mock(AuditService.class);
        service = new MerchantService(merchantRepository, new MerchantMapperImpl(),
                mock(CommissionGroupRepository.class), auditService);
        when(merchantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Merchant existing(DisbursementType type, String accountNumber) {
        Merchant merchant = Merchant.builder().merchantCode("acme").companyName("Acme")
                .disbursementType(type).accountNumber(accountNumber).build();
        merchant.setId(9L);
        when(merchantRepository.findByMerchantCode("acme")).thenReturn(Optional.of(merchant));
        return merchant;
    }

    private static UpdateMerchantRequest update(DisbursementType type, String accountNumber) {
        return UpdateMerchantRequest.builder().companyName("Acme").disbursementType(type)
                .accountNumber(accountNumber).build();
    }

    private AuditLog recordedAudit() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        return captor.getValue().build();
    }

    @Test
    @DisplayName("redirecting the payee is audited: who, merchant, old and new masked to the last 4")
    void payeeChangeIsAudited() {
        existing(DisbursementType.CUSTOMER_MOBILE_WALLET, "263771111111");

        service.updateMerchant("acme", update(DisbursementType.MERCHANT_MOBILE_WALLET, "263779999876"), "admin-sub");

        AuditLog audit = recordedAudit();
        assertThat(audit.getEventType()).isEqualTo(MerchantService.PAYEE_CHANGED_EVENT);
        assertThat(audit.getEntityType()).isEqualTo("MERCHANT");
        assertThat(audit.getEntityId()).isEqualTo("9");
        assertThat(audit.getActorId()).isEqualTo("admin-sub");
        assertThat(audit.getStateTransitionDelta()).isEqualTo(
                "{\"from\":{\"disbursementType\":\"CUSTOMER_MOBILE_WALLET\",\"accountNumber\":\"****1111\"},"
                        + "\"to\":{\"disbursementType\":\"MERCHANT_MOBILE_WALLET\",\"accountNumber\":\"****9876\"}}");
        assertThat(audit.getDetail()).isEqualTo("merchantCode=acme disbursementType CUSTOMER_MOBILE_WALLET -> "
                + "MERCHANT_MOBILE_WALLET, accountNumber ****1111 -> ****9876");
        assertThat(audit.toString()).doesNotContain("263771111111").doesNotContain("263779999876");
    }

    @Test
    @DisplayName("an account-only change is audited too")
    void accountOnlyChangeIsAudited() {
        existing(DisbursementType.MERCHANT_MOBILE_WALLET, "263771111111");

        service.updateMerchant("acme", update(DisbursementType.MERCHANT_MOBILE_WALLET, "263772222222"), "admin-sub");

        assertThat(recordedAudit().getDetail()).endsWith("accountNumber ****1111 -> ****2222");
    }

    @Test
    @DisplayName("an edit that leaves the payee alone writes no payee audit")
    void unchangedPayeeIsNotAudited() {
        existing(DisbursementType.MERCHANT_MOBILE_WALLET, "263771111111");

        service.updateMerchant("acme", update(DisbursementType.MERCHANT_MOBILE_WALLET, "263771111111"), "admin-sub");

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("a new merchant's payee is audited from nothing")
    void createIsAudited() {
        service.createMerchant(CreateMerchantRequest.builder().code("new-co").companyName("New Co")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("263775554321")
                .commissionStructure(CommissionStructure.AGENT_DEFINED).build(), "admin-sub");

        AuditLog audit = recordedAudit();
        assertThat(audit.getActorId()).isEqualTo("admin-sub");
        assertThat(audit.getStateTransitionDelta()).startsWith(
                "{\"from\":{\"disbursementType\":null,\"accountNumber\":null},");
        assertThat(audit.getDetail()).isEqualTo(
                "merchantCode=new-co disbursementType null -> MERCHANT_MOBILE_WALLET, accountNumber null -> ****4321");
    }

    @Test
    @DisplayName("the masked tail is free text, so it is JSON-escaped in the delta")
    void maskedTailIsEscaped() {
        existing(DisbursementType.MERCHANT_MOBILE_WALLET, "263771111111");

        service.updateMerchant("acme", update(DisbursementType.MERCHANT_MOBILE_WALLET, "12345\"\\x"), "admin-sub");

        assertThat(recordedAudit().getStateTransitionDelta()).endsWith("\"accountNumber\":\"****5\\\"\\\\x\"}}");
    }

    @Test
    @DisplayName("findMerchants masks account numbers only when asked")
    void findMerchantsMasksOnRequest() {
        when(merchantRepository.findAll()).thenReturn(List.of(
                Merchant.builder().merchantCode("acme").accountNumber("263771234567").build(),
                Merchant.builder().merchantCode("dflt").accountNumber(null).build()));

        assertThat(service.findMerchants(true).getMerchants()).extracting(MerchantDto::getAccountNumber)
                .containsExactly("****4567", null);
        assertThat(service.findMerchants(false).getMerchants()).extracting(MerchantDto::getAccountNumber)
                .containsExactly("263771234567", null);
    }

    @Test
    @DisplayName("masking keeps the last 4 and hides a value that short entirely")
    void maskAccountNumber() {
        assertThat(MerchantService.maskAccountNumber("263771234567")).isEqualTo("****4567");
        assertThat(MerchantService.maskAccountNumber("12345")).isEqualTo("****2345");
        assertThat(MerchantService.maskAccountNumber("1234")).isEqualTo("****");
        assertThat(MerchantService.maskAccountNumber("")).isEmpty();
        assertThat(MerchantService.maskAccountNumber(null)).isNull();
    }
}
