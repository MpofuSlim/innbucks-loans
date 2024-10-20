package zw.co.reikan.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class FindLoansInternalRequest extends FindLoansRequest {
    private Long userId;
    private String merchantCode;
}
