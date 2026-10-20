package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamChangeNotice;
import jack.digityhuman.examservice.entity.ExamNoticeOutbox;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamChangeNoticeMapper;
import jack.digityhuman.examservice.mapper.ExamNoticeOutboxMapper;
import jack.digityhuman.staffmanagement.api.NotificationPublishService;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishCmd;
import jack.digityhuman.staffmanagement.api.dto.NotificationPublishResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试通知 Outbox Worker（08 §6.1 的"事务提交后投递"环节）。
 *
 * <p><b>职责</b>：把 {@code exam_notice_outbox} 里 {@code status=0} 的行投给通用通知通道。
 * 发送动作与业务事务解耦，所以通知通道短暂不可用只会让 outbox 挂着重试，
 * 不会回滚已经发出去的变更通知记录。
 *
 * <p><b>收件人在本服务解析</b>：收件人 = 该场考试已安排的考生，这是 ExamService 的领域数据。
 * 让通知中心去查 {@code exam_arrange_student} 会变成跨域读表（违反 AGENTS 铁律），
 * 因此这里解析成 userIds 再经 Dubbo 传入。
 *
 * <p><b>幂等</b>：dedupKey 由本服务生成且唯一，通道侧再按 dedupKey 兜一层，
 * 重试不会产生重复通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamNoticeOutboxWorker {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 关掉后只积累 outbox 不投递，便于本地只想跑考试主流程时使用。 */
    @Value("${exam.notice.outbox-enabled:true}")
    private boolean enabled;

    @Value("${exam.notice.outbox-batch-size:20}")
    private int batchSize;

    @Value("${exam.notice.outbox-max-retry:8}")
    private int maxRetry;

    private final ExamNoticeOutboxMapper outboxMapper;
    private final ExamChangeNoticeMapper noticeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamArrangeMapper arrangeMapper;
    private final NotificationPublishService notificationPublishService;

    /**
     * 定时扫一批待发送的 outbox。
     *
     * <p>首轮延迟给足，避开应用启动时 Nacos/Dubbo 尚未就绪的窗口；
     * 之后固定间隔轮询，够用且不引入调度框架。
     */
    @Scheduled(initialDelayString = "${exam.notice.outbox-initial-delay-ms:20000}",
            fixedDelayString = "${exam.notice.outbox-interval-ms:30000}")
    public void drain() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ExamNoticeOutbox> rows = outboxMapper.selectPage(new Page<>(1, Math.max(1, batchSize)),
                Wrappers.<ExamNoticeOutbox>lambdaQuery()
                        .eq(ExamNoticeOutbox::getStatus, 0)
                        .orderByAsc(ExamNoticeOutbox::getCreatedAt)).getRecords();
        for (ExamNoticeOutbox row : rows) {
            // 退避未到的先跳过，下一轮再看
            if (row.getNextRetryAt() != null && row.getNextRetryAt().isAfter(now)) {
                continue;
            }
            deliver(row);
        }
    }

    private void deliver(ExamNoticeOutbox row) {
        Map<String, Object> payload = parsePayload(row.getPayloadJson());
        ExamChangeNotice notice = StringUtils.hasText(row.getNoticeId())
                ? noticeMapper.selectById(row.getNoticeId()) : null;

        // 标题/正文优先取 payload（催促这类非"变更通知"事件没有 exam_change_notice 行），
        // 取不到再回落到发送记录。
        String title = text(payload.get("title"), notice == null ? null : notice.getTitle());
        String content = text(payload.get("content"), notice == null ? null : notice.getContent());
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            fail(row, "通知内容缺失：payload 与发送记录都没有标题/正文");
            return;
        }
        try {
            ExamArrange arrange = arrangeMapper.selectById(row.getArrangeId());
            NotificationPublishCmd cmd = new NotificationPublishCmd();
            cmd.setSourceService("exam");
            cmd.setEventCode(row.getEventCode());
            cmd.setBizType("ARRANGE");
            cmd.setBizId(row.getArrangeId());
            cmd.setDedupKey(row.getDedupKey());
            cmd.setTitle(title);
            cmd.setContent(content);
            cmd.setPayloadJson(row.getPayloadJson());
            // 重要考试 → 通知紧急度 1：移动端推送时可以据此决定是否加提示音/横幅
            cmd.setSeverity(arrange != null && arrange.getImportant() != null && arrange.getImportant() == 1 ? 1 : 0);
            cmd.setChannels("IN_APP");
            cmd.setCreatedBy(notice == null ? "" : notice.getCreatedBy());
            // 催促只发给被点名的考生；没有显式名单（变更通知）才回落到全场考生
            List<String> targets = stringList(payload.get("recipientUserIds"));
            cmd.setRecipientUserIds(targets.isEmpty() ? recipientUserIds(row.getArrangeId()) : targets);

            NotificationPublishResult result = notificationPublishService.publish(cmd);
            succeed(row, notice, result == null ? 0 : result.getRecipientQty());
        } catch (Exception e) {
            // 通道不可用属于可恢复失败：留 status=0 并退避重试
            log.warn("通知 outbox 投递失败，将退避重试：outboxId={} arrangeId={} err={}",
                    row.getId(), row.getArrangeId(), e.getMessage());
            retry(row, e.getMessage());
        }
    }

    /** 收件人 = 该场考试已安排的考生（逻辑删除的已排除，MP 逻辑删除由字段 deleted 控制）。 */
    private List<String> recipientUserIds(String arrangeId) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(arrangeId)) {
            return out;
        }
        for (ExamArrangeStudent s : studentMapper.selectList(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId))) {
            if (StringUtils.hasText(s.getUserId())) {
                out.add(s.getUserId());
            }
        }
        return out;
    }

    private void succeed(ExamNoticeOutbox row, ExamChangeNotice notice, int recipientQty) {
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(1);
        row.setPublishedAt(now);
        row.setErrorMessage("");
        outboxMapper.updateById(row);

        // 催促等事件没有 exam_change_notice 行，只更新 outbox 即可
        if (notice == null) {
            return;
        }
        notice.setStatus(1);
        notice.setSentAt(now);
        notice.setRecipientQty(recipientQty);
        noticeMapper.updateById(notice);
    }

    private void retry(ExamNoticeOutbox row, String message) {
        int retry = row.getRetryCount() == null ? 0 : row.getRetryCount();
        retry++;
        if (retry >= maxRetry) {
            fail(row, "重试 " + retry + " 次仍失败：" + message);
            return;
        }
        row.setRetryCount(retry);
        // 30s 起，逐次翻倍，封顶 10 分钟
        long delaySec = Math.min(600L, 30L << Math.min(retry, 4));
        row.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySec));
        row.setErrorMessage(truncate(message));
        outboxMapper.updateById(row);
    }

    /** 终结失败：outbox 置失败，同时把发送记录标成发送失败，管理端能看到真实结果。 */
    private void fail(ExamNoticeOutbox row, String message) {
        row.setStatus(2);
        row.setErrorMessage(truncate(message));
        outboxMapper.updateById(row);
        ExamChangeNotice notice = StringUtils.hasText(row.getNoticeId())
                ? noticeMapper.selectById(row.getNoticeId()) : null;
        if (notice != null) {
            notice.setStatus(2);
            noticeMapper.updateById(notice);
        }
        log.error("通知 outbox 终结失败：outboxId={} arrangeId={} reason={}",
                row.getId(), row.getArrangeId(), message);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 900 ? text : text.substring(0, 900);
    }

    /**
     * 供业务方"写完 outbox 立刻尝试投递"用（如催促：用户点完要马上看到效果）。
     *
     * <p>失败不抛异常——{@link #deliver} 内部已把失败写进退避重试，
     * 定时任务会在下一轮继续捞，不会因为通道抖动把用户操作判成失败。
     */
    public void deliverNow(ExamNoticeOutbox row) {
        deliver(row);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("通知 payload 解析失败，按空处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String text(Object value, String fallback) {
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return String.valueOf(value);
        }
        return fallback;
    }

    /** payload 里的 recipientUserIds 是 JSON 数组，反序列化后是 List<Object>。 */
    private List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && StringUtils.hasText(String.valueOf(o))) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }
}
