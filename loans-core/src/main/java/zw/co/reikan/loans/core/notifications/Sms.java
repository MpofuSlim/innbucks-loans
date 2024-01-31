package zw.co.reikan.loans.core.notifications;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Sms {
    private String to;
    private String from;
    private String text;
}
