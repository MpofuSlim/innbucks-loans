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
    @ManyToOne
    private User systemUser;
}
