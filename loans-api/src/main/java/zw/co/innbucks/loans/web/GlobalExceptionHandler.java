package zw.co.innbucks.loans.web;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import zw.co.innbucks.loans.core.borrower.AssertionRejectedException;
import zw.co.innbucks.loans.core.borrower.BorrowerSignInUnavailableException;
import zw.co.innbucks.loans.core.borrower.NotOnStaffRegisterException;
import zw.co.innbucks.loans.core.document.DocumentProblem;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDeclinedException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRequestInvalidException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanTermsChangedException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanTermsUnavailableException;
import zw.co.innbucks.loans.core.staff.loan.StepUpRequiredException;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunFailedException;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunResponse;
import zw.co.innbucks.loans.core.document.DocumentRejectedException;
import zw.co.innbucks.loans.core.exception.AccountLockedException;
import zw.co.innbucks.loans.core.exception.BusinessException;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.DisbursementNotAllowedException;
import zw.co.innbucks.loans.core.exception.DuplicateUserByUsernameException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.PendingApplicationException;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.staff.StaffRecordInvalidException;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowResponse;
import zw.co.innbucks.loans.core.staff.StaffUploadRejectedException;
import zw.co.innbucks.loans.core.voucher.VoucherRefusedException;
import zw.co.innbucks.loans.core.voucher.VouchersUnavailableException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders every error as the {@link ApiResult} envelope, with a stable UPPER_SNAKE {@code code} a
 * client can branch on and a {@code message} it can show. An unhandled exception is a generic 500:
 * its message and stack trace go to the log, never to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String INTERNAL_ERROR_MESSAGE = "An unexpected error occurred";
    static final String INSUFFICIENT_ROLE_MESSAGE = "Forbidden - insufficient role";

    /** Bean-validation failures on a request body: one 400 with every failing field and its message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        log.warn("Request validation failed: {}", fields);
        return validationError(fields);
    }

    /** Constraints on query or path parameters. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> invalidParameters(HandlerMethodValidationException ex) {
        Map<String, String> fields = new TreeMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                fields.putIfAbsent(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
        log.warn("Parameter validation failed: {}", fields);
        return validationError(fields);
    }

    /** Constraints checked on a service method rather than at the web edge. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> constraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new TreeMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fields.putIfAbsent(field, violation.getMessage());
        });
        log.warn("Constraint violation: {}", fields);
        return validationError(fields);
    }

    /**
     * An unparseable body: malformed JSON, an enum value that is not one of the names (e.g.
     * "Married" for MARRIED), or a date not in yyyy-MM-dd.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> unreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Malformed request body - check enum values and yyyy-MM-dd dates");
    }

    /** A query or path parameter of the wrong type: a date not in yyyy-MM-dd, an unknown status, a non-numeric id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> parameterTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid value for parameter {}", ex.getName());
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for '" + ex.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> missingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Parameter '" + ex.getParameterName() + "' is required");
    }

    /** Uploaded documents that were not accepted (FR-SSB-005): every one, each with its field and reason. */
    @ExceptionHandler(DocumentRejectedException.class)
    public ResponseEntity<ApiResult<Map<String, List<DocumentProblem>>>> rejectedDocuments(
            DocumentRejectedException ex) {
        log.warn("Documents refused: {}", ex.getProblems().stream()
                .map(problem -> problem.field() + " " + problem.reason()).toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResult.error("INVALID_DOCUMENT",
                ex.getMessage(), Map.of("documents", ex.getProblems())));
    }

    /**
     * A saved application submitted with fields still missing or invalid (FR-SSB-002): every one, keyed by
     * its path, as a refused {@code POST /loans} body lists them.
     */
    @ExceptionHandler(IncompleteApplicationException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> incompleteApplication(IncompleteApplicationException ex) {
        log.warn("Incomplete application refused: {}", ex.getFields().keySet());
        return ResponseEntity.badRequest().body(ApiResult.error("VALIDATION_ERROR", "The application is not complete",
                new TreeMap<>(ex.getFields())));
    }

    /** A staff record changed on the admin screen fails the register's rules (FR-SGL-003): every failing field. */
    @ExceptionHandler(StaffRecordInvalidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> invalidStaffRecord(StaffRecordInvalidException ex) {
        log.warn("Staff record refused: {}", ex.getFields().keySet());
        return ResponseEntity.badRequest().body(ApiResult.error("VALIDATION_ERROR", "Request validation failed",
                new TreeMap<>(ex.getFields())));
    }

    /** Every row of an uploaded staff file was refused: nothing was submitted, and each row says why (FR-SGL-003). */
    @ExceptionHandler(StaffUploadRejectedException.class)
    public ResponseEntity<ApiResult<Map<String, List<StaffRegisterRowResponse>>>> nothingToLoad(
            StaffUploadRejectedException ex) {
        log.warn("Staff file refused: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResult.error("NOTHING_TO_LOAD", ex.getMessage(),
                Map.of("rejected", ex.getRows())));
    }

    /** A username another account already holds. Checked before {@link BusinessException}, its parent. */
    @ExceptionHandler(DuplicateUserByUsernameException.class)
    public ResponseEntity<ApiResult<Void>> usernameTaken(DuplicateUserByUsernameException ex) {
        log.warn("Refused: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "USERNAME_TAKEN", ex.getMessage());
    }

    /** A request a business rule refuses; the message says which rule. */
    @ExceptionHandler({BusinessException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResult<Void>> refusedByRule(RuntimeException ex) {
        log.warn("Request refused: {}", ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResult<Void>> notFound(NotFoundException ex) {
        log.warn("Not found: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /** An unmapped path is a 404, not a 500 that reads like a server fault. Ordinary traffic, so DEBUG. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResult<Void>> noSuchPath(Exception ex) {
        log.debug("No handler: {}", ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
    }

    /** The same applicant already has a loan in flight; nothing was created. */
    @ExceptionHandler(PendingApplicationException.class)
    public ResponseEntity<ApiResult<Void>> applicationPending(PendingApplicationException ex) {
        log.info("Application refused: loan {} for this applicant is still in flight", ex.getPendingLoanId());
        return error(HttpStatus.CONFLICT, "APPLICATION_PENDING", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResult<Void>> conflict(ConflictException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    /** A manual payout the loan's state forbids: the request is valid, paying the loan now is not. */
    @ExceptionHandler(DisbursementNotAllowedException.class)
    public ResponseEntity<ApiResult<Void>> disbursementNotAllowed(DisbursementNotAllowedException ex) {
        log.warn("Manual disbursement refused: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "DISBURSEMENT_NOT_ALLOWED", ex.getMessage());
    }

    /** Two writers raced the same row (@Version): retry after re-reading, not a server fault. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<Void>> concurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        log.warn("Concurrent update: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The resource was modified concurrently - retry");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not allowed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(ex.getHeaders())
                .body(ApiResult.error("METHOD_NOT_ALLOWED", ex.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send application/json");
    }

    /**
     * A failed sign-in. The message is fixed: which of username and password was wrong is not the
     * caller's to learn.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResult<Void>> unauthenticated(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        String message = ex instanceof BadCredentialsException
                ? "Invalid username or password" : "Authentication required";
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /** 423 with when the lock ends, as the ticketing user-service answers it, plus Retry-After. */
    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiResult<AccountLock>> accountLocked(AccountLockedException ex) {
        long retryAfterSeconds = Math.max(1,
                Duration.between(LocalDateTime.now(ZoneOffset.UTC), ex.getLockedUntil()).toSeconds());
        log.warn("Sign-in refused: account locked until {} UTC", ex.getLockedUntil());
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(ApiResult.error("ACCOUNT_LOCKED", ex.getMessage(),
                        new AccountLock(ex.getLockedUntil(), retryAfterSeconds)));
    }

    /**
     * A role refusal from {@code @PreAuthorize}. Thrown inside the handler call, past the filter
     * chain, so the security chain's own 403 writer never sees it; the body is the same one.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResult<Void>> insufficientRole(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", INSUFFICIENT_ROLE_MESSAGE);
    }

    /** A refusal the service raised itself, whose message says why (e.g. the originator approving their own loan). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> accessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    /**
     * A middleware assertion that was not accepted: forged, expired, for someone else, or used before. One opaque
     * answer whatever the reason (the reason is logged and audited), since naming it would help whoever forged it.
     */
    @ExceptionHandler(AssertionRejectedException.class)
    public ResponseEntity<ApiResult<Void>> assertionRejected(AssertionRejectedException ex) {
        return error(HttpStatus.UNAUTHORIZED, "ASSERTION_REJECTED",
                "Assertion rejected - sign in to the SuperApp again");
    }

    /** No middleware key configured, so no assertion can be checked: the server's state, not the caller's request. */
    @ExceptionHandler(BorrowerSignInUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> borrowerSignInUnavailable(BorrowerSignInUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BORROWER_SIGN_IN_UNAVAILABLE", ex.getMessage());
    }

    /**
     * A borrower who cannot take a Staff Grocery Loan now (FR-SGL-029): the message is the plain-language reason to
     * show as it is, and data.reason names it for the app.
     */
    /** An offer run broke off: nothing it did was kept, and {@code data} is the FAILED run it is recorded as. */
    @ExceptionHandler(StaffOfferRunFailedException.class)
    public ResponseEntity<ApiResult<StaffOfferRunResponse>> staffOfferRunFailed(StaffOfferRunFailedException ex) {
        log.warn("Staff offer run {} failed (logged with its cause when it broke off)", ex.run().id());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResult.error("RUN_FAILED",
                ex.getMessage(), ex.run()));
    }

    @ExceptionHandler(StaffLoanDeclinedException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> staffLoanDeclined(StaffLoanDeclinedException ex) {
        log.info("Staff Grocery Loan declined: {}", ex.decline());
        return ResponseEntity.unprocessableEntity().body(ApiResult.error("STAFF_LOAN_DECLINED", ex.getMessage(),
                Map.of("reason", ex.decline().name())));
    }

    /** A field of a borrower's request the journey cannot take: the amount, the device. */
    @ExceptionHandler(StaffLoanRequestInvalidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> staffLoanRequestInvalid(StaffLoanRequestInvalidException ex) {
        log.warn("Staff Grocery Loan request refused: {}", ex.getFields().keySet());
        return validationError(new TreeMap<>(ex.getFields()));
    }

    /**
     * No Staff Grocery Loan agreement is published, so nothing can be accepted: the server's state, not the request.
     */
    @ExceptionHandler(StaffLoanTermsUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> staffLoanTermsUnavailable(StaffLoanTermsUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STAFF_LOAN_TERMS_UNAVAILABLE", ex.getMessage());
    }

    /** The agreement accepted is not the one in force now: the borrower must see the current one first. */
    @ExceptionHandler(StaffLoanTermsChangedException.class)
    public ResponseEntity<ApiResult<Void>> staffLoanTermsChanged(StaffLoanTermsChangedException ex) {
        return error(HttpStatus.CONFLICT, "TERMS_CHANGED", ex.getMessage());
    }

    /**
     * A genuine assertion that is not a PIN or biometric from just now (FR-SGL-028): the app asks for the PIN again.
     */
    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<ApiResult<Void>> stepUpRequired(StepUpRequiredException ex) {
        return error(HttpStatus.UNAUTHORIZED, "STEP_UP_REQUIRED", ex.getMessage());
    }

    /** A genuine sign-in for a phone that is not a current staff member's: the product is not for them (FR-SGL-029). */
    @ExceptionHandler(NotOnStaffRegisterException.class)
    public ResponseEntity<ApiResult<Void>> notOnStaffRegister(NotOnStaffRegisterException ex) {
        return error(HttpStatus.FORBIDDEN, "NOT_ON_STAFF_REGISTER", ex.getMessage());
    }

    /**
     * A till's voucher validation or redemption refused (FR-SGL-036): the code names why (INVALID_VOUCHER_CODE,
     * VOUCHER_NOT_FOUND, VOUCHER_EXPIRED, INSUFFICIENT_BALANCE, ...). Nothing was redeemed.
     */
    @ExceptionHandler(VoucherRefusedException.class)
    public ResponseEntity<ApiResult<Void>> voucherRefused(VoucherRefusedException ex) {
        log.warn("Voucher refused: {}", ex.getRefusal());
        return error(HttpStatus.valueOf(ex.getRefusal().httpStatus()), ex.getRefusal().name(), ex.getMessage());
    }

    /** The voucher code keys are not configured: no voucher can be issued, read in full or redeemed. */
    @ExceptionHandler(VouchersUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> vouchersUnavailable(VouchersUnavailableException ex) {
        log.error("Voucher call refused: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "VOUCHERS_UNAVAILABLE", ex.getMessage());
    }

    /** The SMS, email or WhatsApp provider refused or could not be reached; nothing was changed. */
    @ExceptionHandler(NotificationDeliveryException.class)
    public ResponseEntity<ApiResult<Void>> notificationFailed(NotificationDeliveryException ex) {
        log.warn("Notification delivery failed: {}", ex.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "NOTIFICATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> unhandled(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
    }

    private static ResponseEntity<ApiResult<Map<String, String>>> validationError(Map<String, String> fields) {
        return ResponseEntity.badRequest()
                .body(ApiResult.error("VALIDATION_ERROR", "Request validation failed", new LinkedHashMap<>(fields)));
    }

    private static ResponseEntity<ApiResult<Void>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResult.error(code, message));
    }

    /** When sign-in reopens ({@code lockedUntil} goes out at the market offset) and the seconds until then. */
    public record AccountLock(LocalDateTime lockedUntil, long retryAfterSeconds) {
    }
}
