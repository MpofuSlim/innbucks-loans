package zw.co.innbucks.loans.core.user;

import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The lending portal as its users are told about it. */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.portal")
public class PortalProperties {

    /**
     * Where portal users sign in, e.g. {@code https://lending.innbucks.co.zw}: put in the message that gives someone a
     * temporary password. Blank, the message names the portal without a link. SMS carries it only as a bare address
     * ({@code lending.innbucks.co.zw}), because the SMS gateway refuses {@code :} and {@code /}: an address with a path
     * goes by WhatsApp and email only.
     */
    @Pattern(regexp = "|https?://[^\\s]+", message = "loans.portal.sign-in-url must be blank or an http(s) address")
    private String signInUrl = "";
}
