package zw.co.innbucks.loans.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a collection. A flat record rather than Spring Data's {@code PageImpl}, whose
 * serialised shape is not a supported contract and has changed between versions.
 */
@Schema(description = "One page of results")
public record PageResponse<T>(
        List<T> items,
        @Schema(description = "Zero-based page index", example = "0")
        int page,
        @Schema(description = "Page size applied (a requested size is kept between 1 and 100)", example = "20")
        int size,
        @Schema(description = "Matching items across all pages", example = "1")
        long totalItems,
        @Schema(example = "1")
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return from(page.map(mapper));
    }
}
