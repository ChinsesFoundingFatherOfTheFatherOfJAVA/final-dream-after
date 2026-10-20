package jack.digityhuman.staffmanagement.notification.channel;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道发送器注册表：把容器里所有 {@link ChannelSender} 按渠道码收成一张表。
 *
 * <p>用构造器收集而不是在调用处 if/else，是为了让"新增渠道 = 新增一个 Bean"，
 * 调用方（{@code NotificationPublishServiceImpl}）永远不需要改动。
 */
@Component
public class ChannelSenderRegistry {

    private final Map<String, ChannelSender> senders = new HashMap<>();

    public ChannelSenderRegistry(List<ChannelSender> all) {
        for (ChannelSender sender : all) {
            senders.put(sender.channel(), sender);
        }
    }

    /** 渠道未接入时返回 null，调用方据此写"渠道未接入"失败原因。 */
    public ChannelSender find(String channel) {
        return senders.get(channel);
    }
}
