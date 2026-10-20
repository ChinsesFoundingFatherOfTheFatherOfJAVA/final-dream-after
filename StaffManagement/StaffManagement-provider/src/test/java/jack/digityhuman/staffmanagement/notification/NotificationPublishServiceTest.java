package jack.digityhuman.staffmanagement.notification;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishResult;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.notification.channel.ChannelSenderRegistry;
import jack.digityhuman.staffmanagement.notification.channel.InAppChannelSender;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeEvent;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeRecipient;
import jack.digityhuman.staffmanagement.notification.mapper.SysNoticeEventMapper;
import jack.digityhuman.staffmanagement.notification.mapper.SysNoticeRecipientMapper;
import jack.digityhuman.staffmanagement.notification.service.impl.NotificationPublishServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用通知通道发布/撤销的核心规则：
 * 幂等（dedupKey）、渠道扩展点（未接入渠道必须留失败痕迹）、撤销语义（未读清零 + 不可见）。
 */
class NotificationPublishServiceTest {

    private SysNoticeEventMapper eventMapper;
    private SysNoticeRecipientMapper recipientMapper;
    private NotificationPublishServiceImpl service;

    /**
     * 纯单测没有 Spring 容器，MyBatis-Plus 的实体元信息不会被 mapper 扫描初始化，
     * 而 {@code LambdaUpdateWrapper.set(...)} 是"立即解析列名"的，缺元信息会抛
     * "can not find lambda cache"。这里手动初始化一次，让撤销分支的包装器构造可用。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysNoticeEvent.class);
        TableInfoHelper.initTableInfo(assistant, SysNoticeRecipient.class);
    }

    private void setUp() {
        eventMapper = mock(SysNoticeEventMapper.class);
        recipientMapper = mock(SysNoticeRecipientMapper.class);
        ChannelSenderRegistry registry = new ChannelSenderRegistry(List.of(new InAppChannelSender()));
        service = new NotificationPublishServiceImpl(eventMapper, recipientMapper, registry);
    }

    private NotificationPublishCmd cmd(String dedupKey, String channels, List<String> userIds) {
        NotificationPublishCmd cmd = new NotificationPublishCmd();
        cmd.setSourceService("exam");
        cmd.setEventCode("ARRANGE_CHANGE");
        cmd.setBizType("ARRANGE");
        cmd.setBizId("A1");
        cmd.setDedupKey(dedupKey);
        cmd.setTitle("考试信息变更通知");
        cmd.setContent("时间调整");
        cmd.setChannels(channels);
        cmd.setRecipientUserIds(userIds);
        return cmd;
    }

    @Test
    void 发布_写入事件并按用户去重投递站内通知() {
        setUp();
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(recipientMapper.insert(any(SysNoticeRecipient.class))).thenReturn(1);

        // 重复的 userId + 空值都要被清洗掉
        NotificationPublishResult result = service.publish(cmd("k1", "IN_APP", List.of("13", "13", "", "14")));

        verify(eventMapper).insert(any(SysNoticeEvent.class));
        ArgumentCaptor<SysNoticeRecipient> captor = ArgumentCaptor.forClass(SysNoticeRecipient.class);
        verify(recipientMapper, times(2)).insert(captor.capture());
        List<SysNoticeRecipient> rows = captor.getAllValues();
        assertEquals(2, rows.size());
        assertEquals("13", rows.get(0).getUserId());
        assertEquals("14", rows.get(1).getUserId());
        // 站内渠道落库即送达
        assertEquals(1, rows.get(0).getDeliveredStatus());
        assertEquals(0, rows.get(0).getReadStatus());
        // 正文冗余进投递记录，学员端列表才能单表查
        assertEquals("考试信息变更通知", rows.get(0).getTitle());
        assertEquals("时间调整", rows.get(0).getContent());
        assertEquals(2, result.getRecipientQty());
        assertFalse(result.isDuplicated());
    }

    @Test
    void 发布_dedupKey命中时幂等返回且不重复投递() {
        setUp();
        SysNoticeEvent existing = new SysNoticeEvent();
        existing.setId("E1");
        when(eventMapper.selectOne(any())).thenReturn(existing);
        when(recipientMapper.selectCount(any())).thenReturn(3L);

        NotificationPublishResult result = service.publish(cmd("k1", "IN_APP", List.of("13")));

        assertTrue(result.isDuplicated());
        assertEquals("E1", result.getEventId());
        assertEquals(3, result.getRecipientQty());
        verify(eventMapper, never()).insert(any(SysNoticeEvent.class));
        verify(recipientMapper, never()).insert(any(SysNoticeRecipient.class));
    }

    @Test
    void 发布_未接入渠道写失败原因而不是静默丢弃() {
        setUp();
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(recipientMapper.insert(any(SysNoticeRecipient.class))).thenReturn(1);

        service.publish(cmd("k2", "SMS", List.of("13")));

        ArgumentCaptor<SysNoticeRecipient> captor = ArgumentCaptor.forClass(SysNoticeRecipient.class);
        verify(recipientMapper).insert(captor.capture());
        assertEquals("SMS", captor.getValue().getChannel());
        assertEquals(2, captor.getValue().getDeliveredStatus());
        assertTrue(captor.getValue().getFailureReason().contains("渠道未接入"));
    }

    @Test
    void 发布_缺少dedupKey直接报参数错误() {
        setUp();
        assertThrows(BizException.class, () -> service.publish(cmd("  ", "IN_APP", List.of("13"))));
        verify(eventMapper, never()).insert(any(SysNoticeEvent.class));
    }

    @Test
    void 撤销_未读置已读并把投递记录标记为已撤销() {
        setUp();
        SysNoticeEvent event = new SysNoticeEvent();
        event.setId("E1");
        event.setStatus(0);
        when(eventMapper.selectById("E1")).thenReturn(event);

        CancelNotificationCmd cancel = new CancelNotificationCmd();
        cancel.setEventId("E1");
        cancel.setReason("考试取消");
        service.cancel(cancel);

        ArgumentCaptor<SysNoticeEvent> captor = ArgumentCaptor.forClass(SysNoticeEvent.class);
        verify(eventMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
        // 两次批量更新：先把未读清零，再把全部标记为已撤销
        verify(recipientMapper, times(2)).update(any(), any());
    }

    @Test
    void 撤销_事件不存在按幂等处理不报错() {
        setUp();
        when(eventMapper.selectById("NOPE")).thenReturn(null);
        CancelNotificationCmd cancel = new CancelNotificationCmd();
        cancel.setEventId("NOPE");

        service.cancel(cancel);

        verify(eventMapper, never()).updateById(any(SysNoticeEvent.class));
        verify(recipientMapper, never()).update(any(), any());
    }
}
