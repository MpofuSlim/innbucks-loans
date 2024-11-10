package zw.co.reikan.loans.core.channel;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.user.User;

@Entity
@Table(name = "channel", indexes = {
        @Index(name = "idx_channel_id", columnList = "channel_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel extends BaseEntity {

    public final static String MOBILE_APP_CHANNEL = "bulkit_mobile_app_13a71261-f59c-466a-baca-31f6e2a7832e";
    public final static String WEB_APP_CHANNEL = "bulkit_self_service_web_app_189ae29c-b09a-4ef3-80d4-f3164978d054";
    public final static String ADMIN_PORTAL_CHANNEL = "bulkit_admin_portal_channel_5b2d532e-5c00-4d8d-80df-86b41b42f40f";

    private String channelId;
    private String name;
    @ManyToOne
    private User systemUser;
}
