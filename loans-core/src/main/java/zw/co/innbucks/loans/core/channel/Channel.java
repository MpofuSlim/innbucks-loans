package zw.co.innbucks.loans.core.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.loan.BaseEntity;
import zw.co.innbucks.loans.core.user.User;

/**
 * A channel applications arrive through, such as the SuperApp, recorded on each loan it brings in. It says where
 * an application came from, never who originated it: that is always the signed-in caller (FR-SSB-017).
 */
@Entity
@Table(name = "channels", indexes = {
        @Index(name = "idx_channels_channel_id", columnList = "channel_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel extends BaseEntity {

    @Column(name = "channel_id")
    private String channelId;
    private String name;
    /**
     * The account the channel's integration signs in as. It no longer owns the loans the channel brings in; loans
     * captured before FR-SSB-017 were attributed to it instead of the caller.
     */
    @ManyToOne
    private User systemUser;
}
