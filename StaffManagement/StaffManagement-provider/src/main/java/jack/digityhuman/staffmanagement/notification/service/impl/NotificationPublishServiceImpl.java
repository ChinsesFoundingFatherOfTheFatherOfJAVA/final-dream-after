package jack.digityhuman.staffmanagement.notification.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.api.NotificationPublishService;
import jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishResult;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import jack.digityhuman.staffmanagement.notification.channel.ChannelSender;
import jack.digityhuman.staffmanagement.notification.channel.ChannelSenderRegistry;
import jack.digityhuman.staffmanagement.notification.channel.NoticeChannel;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeEvent;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeRecipient;
import jack.digityhuman.staffmanagement.notification.mapper.SysNoticeEventMapper;
import jack.digityhuman.staffmanagement.notification.mapper.SysNoticeRecipientMapper;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用通知通道的发布实现，同时是 Dubbo 服务提供者（08 §4 / §6）。
 *
 * <p><b>为什么发放在一个事务里</b>：首期只有站内渠道，投递记录落库就是送达，
 * 事件与投递必须是原子的。将来接入推送/短信这类"外部副作用"渠道时，
 * 应改为"事务内只写待送记录 + 事务提交后异步发送"，
 * 否则外部调用超时会拖住整个事务（届时在 {@code ChannelSender} 实现侧引入 outbox 即可，
 * 本类的状态收口逻辑不变）。
 */
@DubboService(version = "1.0.0", timeout = 30_000)
public class NotificationPublishServiceImpl implements NotificationPublishService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublishServiceImpl.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 撤销原因 / 渠道未接入原因的落库字段长度上限。 */
    private static final int REASON_MAX = 900;

    private final SysNoticeEventMapper eventMapper;
    private final SysNoticeRecipientMapper recipientMapper;
    private final ChannelSenderRegistry channelRegistry;

    public NotificationPublishServiceImpl(SysNoticeEventMapper eventMapper,
                                          SysNoticeRecipientMapper recipientMapper,
                                          ChannelSenderRegistry channelRegistry) {
        this.eventMapper = eventMapper;
        this.recipientMapper = recipientMapper;
        this.channelRegistry = channelRegistry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationPublishResult publish(NotificationPublishCmd cmd) {
        validate(cmd);

        // 幂等：dedupKey 命中过就直接返回首次结果，重试不产生重复通知
        SysNoticeEvent existing = eventMapper.selectOne(Wrappers.<SysNoticeEvent>lambdaQuery()
                .eq(SysNoticeEvent::getDedupKey, cmd.getDedupKey()));
        if (existing != null) {
            Long n = recipientMapper.selectCount(Wrappers.<SysNoticeRecipient>lambdaQuery()
                    .eq(SysNoticeRecipient::getEventId, existing.getId()));
            log.info("通知事件已存在，按幂等返回：dedupKey={} eventId={}", cmd.getDedupKey(), existing.getId());
            return result(existing.getId(), n == null ? 0 : n.intValue(), true);
        }

        LocalDateTime now = LocalDateTime.now();
        SysNoticeEvent event = new SysNoticeEvent();
        event.setId(IdWorker.getIdStr());
        event.setSourceService(cmd.getSourceService().trim());
        event.setEventCode(cmd.getEventCode().trim());
        event.setBizType(orEmpty(cmd.getBizType()));
        event.setBizId(orEmpty(cmd.getBizId()));
        event.setTitle(cmd.getTitle());
        event.setContent(cmd.getContent());
        event.setPayloadJson(cmd.getPayloadJson());
        event.setSeverity(cmd.getSeverity() == null ? 0 : cmd.getSeverity());
        event.setChannels(String.join(",", parseChannels(cmd.getChannels())));
        event.setDedupKey(cmd.getDedupKey().trim());
        event.setStatus(0);
        event.setPublishAt(parseTime(cmd.getPublishAt()));
        event.setExpireAt(parseTime(cmd.getExpireAt()));
        event.setCreatedBy(orEmpty(cmd.getCreatedBy()));
        event.setCreatedAt(now);
        eventMapper.insert(event);

        int qty = 0;
        for (String channel : parseChannels(cmd.getChannels())) {
            for (String userId : distinctUserIds(cmd.getRecipientUserIds())) {
                SysNoticeRecipient recipient = buildRecipient(event, channel, userId, now);
                recipientMapper.insert(recipient);
                qty++;
            }
        }
        if (qty == 0) {
            log.warn("通知事件没有收件人，仅留痕：dedupKey={} source={} event={}",
                    event.getDedupKey(), event.getSourceService(), event.getEventCode());
        }
        log.info("通知事件已投递：eventId={} dedupKey={} recipients={}", event.getId(), event.getDedupKey(), qty);
        return result(event.getId(), qty, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(CancelNotificationCmd cmd) {
        if (cmd == null || (!StringUtils.hasText(cmd.getEventId()) && !StringUtils.hasText(cmd.getDedupKey()))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "撤销通知需要 eventId 或 dedupKey");
        }
        SysNoticeEvent event = StringUtils.hasText(cmd.getEventId())
                ? eventMapper.selectById(cmd.getEventId())
                : eventMapper.selectOne(Wrappers.<SysNoticeEvent>lambdaQuery()
                        .eq(SysNoticeEvent::getDedupKey, cmd.getDedupKey().trim()));
        if (event == null) {
            // 撤销一个不存在的事件属于幂等场景（例如重复收到取消指令），不报错
            log.warn("撤销通知命中空事件：eventId={} dedupKey={}", cmd.getEventId(), cmd.getDedupKey());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = StringUtils.hasText(cmd.getReason()) ? cmd.getReason() : "业务方撤销";

        event.setStatus(2);
        eventMapper.updateById(event);

        // 1) 未读的置已读：撤销后不应继续计入未读数
        recipientMapper.update(null, Wrappers.<SysNoticeRecipient>lambdaUpdate()
                .eq(SysNoticeRecipient::getEventId, event.getId())
                .eq(SysNoticeRecipient::getReadStatus, 0)
                .set(SysNoticeRecipient::getReadStatus, 1)
                .set(SysNoticeRecipient::getReadAt, now));
        // 2) 全部标记已撤销：学员端列表按 delivered_status<>3 过滤，撤销后即不可见
        recipientMapper.update(null, Wrappers.<SysNoticeRecipient>lambdaUpdate()
                .eq(SysNoticeRecipient::getEventId, event.getId())
                .set(SysNoticeRecipient::getDeliveredStatus, 3)
                .set(SysNoticeRecipient::getFailureReason, truncate(reason)));
        log.info("通知事件已撤销：eventId={} reason={}", event.getId(), reason);
    }

    // ---------------- 内部 ----------------

    /**
     * 组装一条投递记录并立即结算状态。
     *
     * <p>先算状态再单次 insert（而不是"插入待送 → 发送 → 再 update"），
     * 首期站内渠道下能省掉一半写库；渠道发送器约定只返回结果、不改状态，
     * 状态收口集中在这里，接推送/短信时不需要每个渠道各写一套。
     */
    private SysNoticeRecipient buildRecipient(SysNoticeEvent event, String channel, String userId,
                                             LocalDateTime now) {
        SysNoticeRecipient r = new SysNoticeRecipient();
        r.setId(IdWorker.getIdStr());
        r.setEventId(event.getId());
        r.setUserId(userId);
        r.setChannel(channel);
        r.setSourceService(event.getSourceService());
        r.setBizType(event.getBizType());
        r.setBizId(event.getBizId());
        r.setTitle(event.getTitle());
        r.setContent(event.getContent());
        r.setReadStatus(0);
        r.setCreatedAt(now);

        ChannelSender sender = channelRegistry.find(channel);
        String reason = sender == null ? "渠道未接入：" + channel : sender.send(r, event);
        if (reason == null) {
            r.setDeliveredStatus(1);
            r.setDeliveredAt(now);
            r.setFailureReason("");
        } else {
            r.setDeliveredStatus(2);
            r.setFailureReason(truncate(reason));
            log.warn("通知投递失败：eventId={} channel={} userId={} reason={}",
                    event.getId(), channel, userId, reason);
        }
        return r;
    }

    private void validate(NotificationPublishCmd cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "通知入参不能为空");
        }
        if (!StringUtils.hasText(cmd.getDedupKey())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "dedupKey 不能为空（幂等必需）");
        }
        if (!StringUtils.hasText(cmd.getSourceService())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "sourceService 不能为空");
        }
        if (!StringUtils.hasText(cmd.getEventCode())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "eventCode 不能为空");
        }
        if (!StringUtils.hasText(cmd.getTitle())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "通知标题不能为空");
        }
        if (!StringUtils.hasText(cmd.getContent())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "通知正文不能为空");
        }
    }

    /** 渠道解析：空值回落站内；顺带 trim 与去重，避免 "IN_APP,,SMS" 这类脏数据。 */
    private List<String> parseChannels(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (StringUtils.hasText(raw)) {
            for (String c : raw.split(",")) {
                if (StringUtils.hasText(c)) {
                    out.add(c.trim().toUpperCase());
                }
            }
        }
        if (out.isEmpty()) {
            out.add(NoticeChannel.IN_APP);
        }
        return new ArrayList<>(out);
    }

    /** 收件人去重并丢弃空值；顺序保留，便于排查是谁投的。 */
    private List<String> distinctUserIds(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String u : raw) {
                if (StringUtils.hasText(u)) {
                    out.add(u.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private LocalDateTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim().replace('T', ' ').substring(0, 19), TIME);
        } catch (RuntimeException e) {
            log.warn("通知时间格式无法解析，按空处理：{}", raw);
            return null;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= REASON_MAX ? text : text.substring(0, REASON_MAX);
    }

    private String orEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private NotificationPublishResult result(String eventId, int qty, boolean duplicated) {
        NotificationPublishResult r = new NotificationPublishResult();
        r.setEventId(eventId);
        r.setRecipientQty(qty);
        r.setDuplicated(duplicated);
        return r;
    }
}
