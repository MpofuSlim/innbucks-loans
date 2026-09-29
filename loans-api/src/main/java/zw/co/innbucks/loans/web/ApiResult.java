package zw.co.innbucks.loans.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The response envelope every endpoint answers with, success or failure:
 * {@code {"code": ..., "message": ..., "data": ...}}. The same shape as the rest of the InnBucks
 * fleet, so a client reads one contract across services. {@code data} is left out when there is
 * nothing to carry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response envelope: code, message and, when there is one, data")
public record ApiResult<T>(
        @Schema(description = "OK or CREATED on success; an UPPER_SNAKE error code otherwise", example = "OK")
        String code,
        @Schema(description = "Human-readable outcome", example = "Success")
        String message,
        T data) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>("OK", "Success", data);
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        return new ApiResult<>("OK", message, data);
    }

    public static <T> ApiResult<T> created(T data) {
        return new ApiResult<>("CREATED", "Created", data);
    }

    public static <T> ApiResult<T> error(String code, String message) {
        return new ApiResult<>(code, message, null);
    }

    public static <T> ApiResult<T> error(String code, String message, T data) {
        return new ApiResult<>(code, message, data);
    }
}
