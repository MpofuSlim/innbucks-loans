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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.notification.LaunchStaffBroadcastRequest;
import zw.co.innbucks.loans.core.staff.notification.StaffLaunchPreviewResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationBroadcastResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationChannel;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationDispatchResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationDispatchStatus;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationOutboundStatus;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationQueueResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationSummaryResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesRequest;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff notifications", description = "What staff are told about the Staff Grocery Loan (FR-SGL-019 to"
        + " FR-SGL-024). Every member a weekly run makes an offer to, new or refreshed, gets a notification: kept here as"
        + " their in-app inbox (the SuperApp reads it at GET /borrower/notifications), and sent to their phone by SMS"
        + " through the InnBucks notification API, by WhatsApp when the SMS fails. The product launch is announced once"
        + " to the whole register. A member can opt out of the phone messages and still see and take up offers in-app,"
        + " and a member who keeps ignoring offers is messaged less often. Every attempt on every channel is logged. A"
        + " notification is created at most once per offer and once per member per broadcast, so running again never"
        + " tells anyone twice.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffNotificationController {

    private static final String SENDERS = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String PREFERENCE_WRITERS = "hasAnyRole('HUMAN_CAPITAL','CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String RANGE_REFUSED = """
            {
              "code": "INVALID_REQUEST",
              "message": "from (2026-10-09) must not be after to (2026-10-05)"
            }""";

    private final StaffNotificationService notificationService;
    private final StaffOfferMessagesService offerMessagesService;

    @Operation(summary = "Staff notifications",
            description = "Newest first, each with every attempt to send it (dispatches). The in-app copy is stored"
                    + " when the notification is created; outboundStatus says where the message to the phone stands:"
                    + " PENDING, SENDING, SENT (deliveredChannel SMS, or WHATSAPP when the SMS failed), FAILED (every"
                    + " channel failed; the in-app copy is all the member has) or SKIPPED (skipReason OPTED_OUT,"
                    + " FREQUENCY_CAP, or OFFER_CLOSED when the offer closed before it could be sent). fromDate and"
                    + " toDate are market days, on when the notification was created.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_NOTIFICATIONS))),
            @ApiResponse(responseCode = "400", description = "An unknown enum value, or fromDate after toDate",
                    content = @Content(examples = {
                            @ExampleObject(name = "Unknown value", value = """
                                    {
                                      "code": "INVALID_PARAMETER",
                                      "message": "Invalid value for 'template'"
                                    }"""),
                            @ExampleObject(name = "Range", value = RANGE_REFUSED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-notifications")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffNotificationResponse>> notifications(
            @Parameter(description = "Only this employee's", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Only this template", example = "OFFER_NEW")
            @RequestParam(required = false) StaffNotificationTemplate template,
            @Parameter(description = "Only those whose phone message stands here", example = "FAILED")
            @RequestParam(required = false) StaffNotificationOutboundStatus outboundStatus,
            @Parameter(description = "Only this offer run's", example = "1") @RequestParam(required = false) Long runId,
            @Parameter(description = "Only this broadcast's", example = "1")
            @RequestParam(required = false) Long broadcastId,
            @Parameter(description = "Created on or after this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Created on or before this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(notificationService.notifications(employeeNumber, template,
                outboundStatus, runId, broadcastId, fromDate, toDate, Paging.of(page, size))));
    }

    @Operation(summary = "How staff notifications fared",
            description = "Counts for a run (runId), a broadcast (broadcastId), a template or a period, or all of them:"
                    + " how many notifications, where their phone messages stand, why any were skipped, and which"
                    + " channel took those sent. Every key is present, 0 where nothing applies.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_NOTIFICATION_RUN_SUMMARY))),
            @ApiResponse(responseCode = "400", description = "fromDate after toDate",
                    content = @Content(examples = @ExampleObject(RANGE_REFUSED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-notifications/summary")
    @PreAuthorize(READERS)
    public ApiResult<StaffNotificationSummaryResponse> summary(
            @Parameter(description = "Only this offer run's", example = "1") @RequestParam(required = false) Long runId,
            @Parameter(description = "Only this broadcast's", example = "1")
            @RequestParam(required = false) Long broadcastId,
            @Parameter(description = "Only this template", example = "OFFER_NEW")
            @RequestParam(required = false) StaffNotificationTemplate template,
            @Parameter(description = "Created on or after this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Created on or before this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResult.ok(notificationService.summary(runId, broadcastId, template, fromDate, toDate));
    }

    @Operation(summary = "The dispatch log",
            description = "Every attempt to reach a staff member, newest first (FR-SGL-023): recipient, channel,"
                    + " template and its version, when, and what happened. IN_APP is STORED when the notification is"
                    + " created; SMS and WHATSAPP are SENT (accepted by the gateway; whether it reached the handset is"
                    + " not reported back) or FAILED with the reason. fromDate and toDate are market days, on the"
                    + " attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_NOTIFICATION_DISPATCHES))),
            @ApiResponse(responseCode = "400", description = "An unknown enum value, or fromDate after toDate",
                    content = @Content(examples = {
                            @ExampleObject(name = "Unknown value", value = """
                                    {
                                      "code": "INVALID_PARAMETER",
                                      "message": "Invalid value for 'channel'"
                                    }"""),
                            @ExampleObject(name = "Range", value = RANGE_REFUSED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-notification-dispatches")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffNotificationDispatchResponse>> dispatches(
            @Parameter(description = "Only this employee's", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "Only this channel", example = "SMS")
            @RequestParam(required = false) StaffNotificationChannel channel,
            @Parameter(description = "Only attempts with this outcome", example = "FAILED")
            @RequestParam(required = false) StaffNotificationDispatchStatus status,
            @Parameter(description = "Only this template", example = "OFFER_NEW")
            @RequestParam(required = false) StaffNotificationTemplate template,
            @Parameter(description = "Only this offer run's", example = "1") @RequestParam(required = false) Long runId,
            @Parameter(description = "Only this broadcast's", example = "1")
            @RequestParam(required = false) Long broadcastId,
            @Parameter(description = "Attempted on or after this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Attempted on or before this market day", example = "2026-10-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(notificationService.dispatches(employeeNumber, channel, status, template,
                runId, broadcastId, fromDate, toDate, Paging.of(page, size))));
    }

    @Operation(summary = "Send what is waiting",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Starts sending every notification still PENDING, at the"
                    + " configured pace, and answers at once with how many there were. Sending normally starts by"
                    + " itself when a run or broadcast commits; this picks up any left behind by a restart, where the"
                    + " scheduled jobs are off. A notification is never sent twice, so calling it again is safe.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Sending started",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "ACCEPTED",
                              "message": "Sending 4 pending notifications",
                              "data": {
                                "pending": 4
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping("/staff-notifications/dispatch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(SENDERS)
    public ApiResult<StaffNotificationQueueResponse> dispatchPending() {
        StaffNotificationQueueResponse queue = notificationService.dispatchPending();
        return new ApiResult<>("ACCEPTED", "Sending " + queue.pending() + " pending notifications", queue);
    }

    @Operation(summary = "Preview the launch broadcast",
            description = "CREDIT_MANAGER or SUPER_ADMIN. What the launch announcement says and how many it would go"
                    + " to now: every member of the staff register who has not left, whether or not they hold an offer."
                    + " Members opted out of offer messages get the in-app copy only. alreadySentAs names the broadcast"
                    + " once it has gone; it is sent once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LAUNCH_PREVIEW))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-notification-broadcasts/launch-preview")
    @PreAuthorize(SENDERS)
    public ApiResult<StaffLaunchPreviewResponse> launchPreview() {
        return ApiResult.ok(notificationService.launchPreview());
    }

    @Operation(summary = "Announce the launch to all staff",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Once only (FR-SGL-020). Every member of the staff register"
                    + " who has not left gets the announcement in-app at once, and by SMS (WhatsApp if the SMS fails)"
                    + " at the configured pace, so the gateways are not flooded. expectedRecipients must equal the"
                    + " preview's recipients: if the register has changed since, it is refused and nothing is sent."
                    + " Delivery per recipient is in the dispatch log and the notifications list (broadcastId). Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Announced",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_LAUNCH_SENT))),
            @ApiResponse(responseCode = "400", description = "expectedRecipients missing or below 1",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "expectedRecipients": "expectedRecipients is required: the recipients count from the launch preview"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "Already announced, or the register changed since the"
                    + " preview",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already announced", value = ApiExamples.STAFF_LAUNCH_ALREADY_SENT),
                            @ExampleObject(name = "Register changed", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "The staff register now has 1241 members to tell, not the 1240 confirmed; check the launch preview again"
                                    }""")}))
    })
    @PostMapping("/staff-notification-broadcasts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SENDERS)
    public ApiResult<StaffNotificationBroadcastResponse> launch(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_LAUNCH_REQUEST)))
            @Valid @RequestBody LaunchStaffBroadcastRequest request) {
        StaffNotificationBroadcastResponse broadcast = notificationService.launch(request);
        return new ApiResult<>("CREATED", "Launch announced to " + broadcast.recipients() + " staff: stored in-app now,"
                + " and sent to their phones at a measured pace", broadcast);
    }

    @Operation(summary = "Broadcasts",
            description = "Every broadcast sent, newest first, with how it has fared so far.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_BROADCASTS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-notification-broadcasts")
    @PreAuthorize(READERS)
    public ApiResult<List<StaffNotificationBroadcastResponse>> broadcasts() {
        return ApiResult.ok(notificationService.broadcasts());
    }

    @Operation(summary = "A member's offer messages setting",
            description = "Whether the member is sent SMS and WhatsApp messages about offers and the launch. Offers"
                    + " are made and shown in-app either way.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_MESSAGES))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the staff register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND)))
    })
    @GetMapping("/staff-members/{employeeNumber}/offer-messages")
    @PreAuthorize(READERS)
    public ApiResult<StaffOfferMessagesResponse> offerMessages(
            @Parameter(description = "Employee number", example = "E1043") @PathVariable String employeeNumber) {
        return ApiResult.ok(offerMessagesService.get(employeeNumber));
    }

    @Operation(summary = "Opt a member out of offer messages, or back in",
            description = "HUMAN_CAPITAL, CREDIT_MANAGER or SUPER_ADMIN, on the member's request (FR-SGL-022) when"
                    + " they ask by phone or at the desk; in the SuperApp they choose it themselves (PUT"
                    + " /borrower/offer-messages). Opted out, they are sent no SMS or WhatsApp about"
                    + " offers or the launch, including messages already queued; offers are still made and still"
                    + " appear in-app, so they can still apply. A reason is required. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_OFFER_MESSAGES_OPTED_OUT))),
            @ApiResponse(responseCode = "400", description = "A missing field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "reason": "A reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not HUMAN_CAPITAL, CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not on the staff register",
                    content = @Content(examples = @ExampleObject(ApiExamples.STAFF_MEMBER_NOT_FOUND)))
    })
    @PutMapping("/staff-members/{employeeNumber}/offer-messages")
    @PreAuthorize(PREFERENCE_WRITERS)
    public ApiResult<StaffOfferMessagesResponse> setOfferMessages(
            @Parameter(description = "Employee number", example = "E1043") @PathVariable String employeeNumber,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.STAFF_OFFER_MESSAGES_REQUEST)))
            @Valid @RequestBody StaffOfferMessagesRequest request) {
        StaffOfferMessagesResponse response = offerMessagesService.set(employeeNumber, request);
        return new ApiResult<>("OK", response.optedOut()
                ? "Employee " + response.employeeNumber() + " will no longer be sent offer messages; offers still"
                + " appear in the app"
                : "Employee " + response.employeeNumber() + " will be sent offer messages again", response);
    }
}
