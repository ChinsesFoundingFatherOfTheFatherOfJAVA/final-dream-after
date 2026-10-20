package jack.digityhuman.staffmanagement.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 移动端设备推送令牌（{@code sys_device_token}），首期只建表不投递。
 *
 * <p>提前建模的原因：推送渠道（个推/极光/FCM）与短信渠道接入时，
 * 唯一需要新增的就是"用户 → 设备令牌"的映射；先把表立起来，
 * 客户端上线时只需补注册接口与一个 {@code ChannelSender} 实现，业务侧零改动。
 */
@Data
@TableName("sys_device_token")
public class SysDeviceToken {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    /** ANDROID / IOS */
    private String platform;

    /** GETUI / JPUSH / FCM / APNS */
    private String provider;

    private String token;
    private String appVersion;
    private Integer enabled;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
