package zw.co.reikan.loans.core.channel;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.user.User;

@Entity
@Table(name = "channel", indexes = {
        @Index(name = "idx_channel_id", columnList = "channel_id")
})
@Data
public class Channel extends BaseEntity {
    private String channelId;
    private String name;
    @ManyToOne
    private User systemUser;
}
