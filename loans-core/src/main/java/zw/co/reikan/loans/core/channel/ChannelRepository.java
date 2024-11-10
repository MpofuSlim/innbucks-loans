package zw.co.reikan.loans.core.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, Long> {

    Optional<Channel> findChannelByChannelId(String channelId);

}
