package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.staff.notification.BorrowerOfferMessages;
import zw.co.innbucks.loans.core.staff.notification.BorrowerOfferMessagesRequest;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxNotification;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxReadAll;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxService;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxUnread;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.BorrowerApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Borrower: notifications (SuperApp)", description = "The borrower's inbox and their choice about offer"
        + " messages (FR-SGL-019, FR-SGL-020, FR-SGL-022), for the borrower session's own staff member. The inbox holds"
        + " every Staff Grocery Loan notification made for them, newest first: each new or renewed offer and the"
        + " product launch, whether or not it also reached their phone. Opting out stops the SMS and WhatsApp messages"
        + " only: offers are still made and still appear in the inbox and on the tile.")
@RestController
@RequestMapping(ApiPaths.BASE + "/borrower")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@PreAuthorize("hasRole('BORROWER')")
public class BorrowerNotificationController {

    private final StaffInboxService inboxService;
    private final StaffOfferMessagesService offerMessagesService;

    @Operation(summary = "The inbox",
            description = "BORROWER. Newest first, paged (page from 0, size 20 by default, at most 100). kind is"
                    + " OFFER_NEW, OFFER_REFRESHED or LAUNCH. An offer notification carries its offerId and"
                    + " offerOpen: show \"Accept\" only while offerOpen is true; otherwise the tile has what they can"
                    + " do now. readAt is absent while unread.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.INBOX))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/notifications")
    public ApiResult<PageResponse<StaffInboxNotification>> inbox(
            @Parameter(hidden = true) JwtAuthenticationToken authentication,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false)
            Integer size) {
        return ApiResult.ok(PageResponse.from(inboxService.inbox(staffMemberId(authentication),
                Paging.of(page, size))));
    }

    @Operation(summary = "How many are unread",
            description = "BORROWER. The badge on the inbox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.INBOX_UNREAD))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/notifications/unread-count")
    public ApiResult<StaffInboxUnread> unread(@Parameter(hidden = true) JwtAuthenticationToken authentication) {
        return ApiResult.ok(inboxService.unread(staffMemberId(authentication)));
    }

    @Operation(summary = "Mark one read",
            description = "BORROWER. When they open it. Reading it again changes nothing: the first read time is kept."
                    + " Another member's notification is not found.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked read; the notification",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.INBOX_READ))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such notification of theirs",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.INBOX_NOT_FOUND)))
    })
    @PostMapping("/notifications/{notificationId}/read")
    public ApiResult<StaffInboxNotification> read(@Parameter(hidden = true) JwtAuthenticationToken authentication,
                                                  @PathVariable Long notificationId) {
        return ApiResult.ok("Marked read", inboxService.read(staffMemberId(authentication), notificationId));
    }

    @Operation(summary = "Mark all read",
            description = "BORROWER. Every unread notification of theirs; marked says how many there were.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked read",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.INBOX_READ_ALL))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping("/notifications/read-all")
    public ApiResult<StaffInboxReadAll> readAll(@Parameter(hidden = true) JwtAuthenticationToken authentication) {
        StaffInboxReadAll marked = inboxService.readAll(staffMemberId(authentication));
        return ApiResult.ok(marked.marked() + (marked.marked() == 1 ? " notification" : " notifications")
                + " marked read", marked);
    }

    @Operation(summary = "Whether they receive offer messages",
            description = "BORROWER. optedOut true: no SMS or WhatsApp about offers or the launch. updatedAt is when"
                    + " it was last changed, by them or by Human Capital on their behalf; absent when never.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.OFFER_MESSAGES))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/offer-messages")
    public ApiResult<BorrowerOfferMessages> offerMessages(
            @Parameter(hidden = true) JwtAuthenticationToken authentication) {
        return ApiResult.ok(offerMessagesService.forMember(staffMemberId(authentication)));
    }

    @Operation(summary = "Stop or restart offer messages",
            description = "BORROWER (FR-SGL-022). optedOut true stops SMS and WhatsApp messages about offers and the"
                    + " launch, those already queued included; false starts them again. Offers are still made and"
                    + " still appear in the inbox and on the tile, so they can still apply. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.OFFER_MESSAGES_OPTED_OUT))),
            @ApiResponse(responseCode = "400", description = "optedOut missing",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "optedOut": "optedOut is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PutMapping("/offer-messages")
    public ApiResult<BorrowerOfferMessages> chooseOfferMessages(
            @Parameter(hidden = true) JwtAuthenticationToken authentication,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(BorrowerApiExamples.OFFER_MESSAGES_REQUEST)))
            @Valid @RequestBody BorrowerOfferMessagesRequest request) {
        BorrowerOfferMessages chosen = offerMessagesService.chooseForMember(staffMemberId(authentication),
                request.getOptedOut());
        return ApiResult.ok(chosen.optedOut() ? "Offer messages stopped; offers still appear in the app"
                : "Offer messages restarted", chosen);
    }

    private static long staffMemberId(JwtAuthenticationToken authentication) {
        return JwtService.borrowerStaffMemberId(authentication.getToken());
    }
}
