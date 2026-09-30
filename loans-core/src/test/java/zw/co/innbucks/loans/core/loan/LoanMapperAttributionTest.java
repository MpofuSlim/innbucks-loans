package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/** The loan views name who originated each application and the channel it came through (FR-SSB-017). */
class LoanMapperAttributionTest {

    private final LoanMapper mapper = new LoanMapperImpl();

    private static User user(String username, String firstName, String lastName) {
        User user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        return user;
    }

    @Test
    @DisplayName("both views carry the originator's username and name, and the channel it came through")
    void viewsNameTheOriginatorAndChannel() {
        Loan loan = Loan.builder().createdBy("tmoyo").createdByUser(user("tmoyo", "Tendai", "Moyo"))
                .channel(Channel.builder().channelId("superapp").name("InnBucks SuperApp").build()).build();

        LoanResponse full = mapper.toResponse(loan);
        LoanSummaryResponse row = mapper.toSummary(loan);

        assertThat(full.getCreatedBy()).isEqualTo("tmoyo");
        assertThat(full.getCreatedByName()).isEqualTo("Tendai Moyo");
        assertThat(full.getChannelId()).isEqualTo("superapp");
        assertThat(full.getChannelName()).isEqualTo("InnBucks SuperApp");
        assertThat(row.getCreatedBy()).isEqualTo("tmoyo");
        assertThat(row.getCreatedByName()).isEqualTo("Tendai Moyo");
        assertThat(row.getChannelId()).isEqualTo("superapp");
        assertThat(row.getChannelName()).isEqualTo("InnBucks SuperApp");
    }

    @Test
    @DisplayName("a portal loan has no channel; a name is whatever parts are recorded, and none is null")
    void portalLoanAndPartialNames() {
        Loan portal = Loan.builder().createdBy("tmoyo").createdByUser(user("tmoyo", " Tendai ", null)).build();
        Loan unnamed = Loan.builder().createdBy("superapp-service")
                .createdByUser(user("superapp-service", null, " ")).build();
        Loan legacy = Loan.builder().createdBy("SYSTEM_USER").build();

        assertThat(mapper.toResponse(portal).getChannelId()).isNull();
        assertThat(mapper.toResponse(portal).getChannelName()).isNull();
        assertThat(mapper.toResponse(portal).getCreatedByName()).isEqualTo("Tendai");
        assertThat(mapper.toSummary(unnamed).getCreatedByName()).isNull();
        assertThat(mapper.toSummary(legacy).getCreatedByName()).isNull();
        assertThat(mapper.toSummary(legacy).getCreatedBy()).isEqualTo("SYSTEM_USER");
    }
}
