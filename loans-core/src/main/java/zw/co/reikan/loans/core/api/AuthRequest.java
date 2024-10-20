package zw.co.reikan.loans.core.api;

import lombok.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class AuthRequest {
    private String username;
    private String password;
}
