package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftResponse;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftService;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftStatus;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application drafts endpoints (FR-SSB-002): the body reaches the service as sent (a merge patch is only
 * meaningful if a null survives the trip), the answers carry their codes, and an incomplete submission is
 * listed like a refused {@code POST /loans}. No database, no Spring context.
 */
class LoanApplicationDraftWebContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/lending/v1/loan-application-drafts";

    private LoanApplicationDraftService draftService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        draftService = mock(LoanApplicationDraftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LoanApplicationDraftController(draftService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static LoanApplicationDraftResponse openDraft() {
        return new LoanApplicationDraftResponse(7L, LoanApplicationDraftStatus.OPEN,
                JSON.readTree("{\"firstName\":\"Tatenda\"}"), List.of(),
                Map.of("ecNumber", "EC number is required"), false, null, null, null, null, null);
    }

    @Test
    @DisplayName("a draft can be started with no body, or with some fields: 201 Draft saved")
    void start() throws Exception {
        when(draftService.create(any())).thenReturn(openDraft());

        mvc.perform(post(BASE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Draft saved"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.application.firstName").value("Tatenda"))
                .andExpect(jsonPath("$.data.validationErrors.ecNumber").value("EC number is required"))
                .andExpect(jsonPath("$.data.complete").value(false))
                .andExpect(jsonPath("$.data.loanId").doesNotExist());
        verify(draftService).create(isNull());

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Tatenda\"}"))
                .andExpect(status().isCreated());
        verify(draftService).create(JSON.readTree("{\"firstName\":\"Tatenda\"}"));
    }

    @Test
    @DisplayName("a save reaches the service with its nulls, as JSON or as a merge patch")
    void saveKeepsNulls() throws Exception {
        when(draftService.update(eq(7L), any())).thenReturn(openDraft());
        String changes = "{\"tenor\":12,\"placeOfBirth\":null,\"employmentDetail\":{\"grade\":\"E1\"}}";

        for (String contentType : List.of("application/merge-patch+json", "application/json")) {
            mvc.perform(patch(BASE + "/7").contentType(contentType).content(changes))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("Draft saved"));
        }
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(draftService, times(2)).update(eq(7L), sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(node -> {
            assertThat(node.has("placeOfBirth")).isTrue();
            assertThat(node.get("placeOfBirth").isNull()).isTrue();
            assertThat(node).isEqualTo(JSON.readTree(changes));
        });
    }

    @Test
    @DisplayName("submitting answers like POST /loans: 201 Loan sent for approval, with the loan's reference")
    void submit() throws Exception {
        when(draftService.submit(7L)).thenReturn(new LoanApplicationResponse(43L, "000000043", LoanApprovalStatus.NEW));

        mvc.perform(post(BASE + "/7/submission"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Loan sent for approval"))
                .andExpect(jsonPath("$.data.id").value(43))
                .andExpect(jsonPath("$.data.reference").value("000000043"))
                .andExpect(jsonPath("$.data.ssbApprovalStatus").value("NEW"));
    }

    @Test
    @DisplayName("an incomplete submission lists every field by its full path, as a refused POST /loans does")
    void incompleteSubmission() throws Exception {
        when(draftService.submit(7L)).thenThrow(new IncompleteApplicationException(Map.of(
                "nextOfKin", "Next of kin is required",
                "employmentDetail.grade", "Grade or notch is required")));

        mvc.perform(post(BASE + "/7/submission"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("The application is not complete"))
                .andExpect(jsonPath("$.data['employmentDetail.grade']").value("Grade or notch is required"))
                .andExpect(jsonPath("$.data.nextOfKin").value("Next of kin is required"));
    }

    @Test
    @DisplayName("someone else's or an expired draft is a 404, a submitted one a 409, a bad value a 400")
    void refusals() throws Exception {
        when(draftService.get(8L)).thenThrow(new NotFoundException("Draft 8 not found"));
        when(draftService.update(eq(7L), any()))
                .thenThrow(new ConflictException("Draft 7 was already submitted as loan 000000043"));
        when(draftService.update(eq(9L), any()))
                .thenThrow(new IllegalArgumentException("Invalid value for 'dateOfBirth'"));

        mvc.perform(get(BASE + "/8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Draft 8 not found"));
        mvc.perform(patch(BASE + "/7").contentType(MediaType.APPLICATION_JSON).content("{\"tenor\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Draft 7 was already submitted as loan 000000043"));
        mvc.perform(patch(BASE + "/9").contentType(MediaType.APPLICATION_JSON).content("{\"dateOfBirth\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'dateOfBirth'"));
    }

    @Test
    @DisplayName("a path that names no document type is a 400, not a lookup")
    void unknownDocumentType() throws Exception {
        mvc.perform(get(BASE + "/7/documents/SELFIE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'documentType'"));
        verifyNoInteractions(draftService);

        mvc.perform(get(BASE + "/7/documents/PAYSLIP")).andExpect(status().isOk());
        verify(draftService).document(7L, DocumentType.PAYSLIP);
    }

    @Test
    @DisplayName("the list pages like every other list: 20 by default, never more than 100")
    void listPages() throws Exception {
        when(draftService.list(any())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get(BASE).param("page", "2").param("size", "500")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(draftService, times(2)).list(pages.capture());
        assertThat(pages.getAllValues().getFirst().getPageSize()).isEqualTo(20);
        assertThat(pages.getAllValues().get(1).getPageNumber()).isEqualTo(2);
        assertThat(pages.getAllValues().get(1).getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("discarding answers Draft discarded with no data")
    void discard() throws Exception {
        mvc.perform(delete(BASE + "/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Draft discarded"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(draftService).discard(7L);
    }
}
