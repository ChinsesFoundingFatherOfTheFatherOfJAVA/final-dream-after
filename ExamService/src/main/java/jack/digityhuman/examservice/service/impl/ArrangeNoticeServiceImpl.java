package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeNoticeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeNoticeRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.entity.ExamArrangeOperationLog;
import jack.digityhuman.examservice.entity.ExamArrangeStudent;
import jack.digityhuman.examservice.entity.ExamChangeNotice;
import jack.digityhuman.examservice.entity.ExamNoticeOutbox;
import jack.digityhuman.examservice.mapper.ExamArrangeMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeOperationLogMapper;
import jack.digityhuman.examservice.mapper.ExamArrangeStudentMapper;
import jack.digityhuman.examservice.mapper.ExamChangeNoticeMapper;
import jack.digityhuman.examservice.mapper.ExamNoticeOutboxMapper;
import jack.digityhuman.examservice.service.ArrangeNoticeService;
import jack.digityhuman.staffmanagement.api.OrgQueryService;
import jack.digityhuman.staffmanagement.api.NotificationPublishService;
import jack.digityhuman.staffmanagement.api.dto.CancelNotificationCmd;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考试变更通知实现（施工序阶段 7）。
 *
 * <p>本模块只负责「发生了什么」：事务内写 {@code exam_change_notice}（管理端发送记录）与
 * {@code exam_notice_outbox}（可靠投递记录）。真正的送达、已读、未读数归通用通知通道
 * （见 {@code 08-通用通知通道设计.md}）；在该通道落地前，outbox 行保持 {@code status=0 待发送}，
 * 由将来的 outbox worker 调 {@code NotificationPublishService#publish}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArrangeNoticeServiceImpl implements ArrangeNoticeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int ERR_NOT_FOUND = 40101;
    private static final int ERR_EMPTY = 40112;
    private static final int ERR_CHANNEL = 40113;
    private static final String EVENT_CODE = "ARRANGE_CHANGE";

    /** 变更字段 → 中文名（用于默认通知正文）。 */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("name", "考试名称"),
            Map.entry("catalogId", "考试分类"),
            Map.entry("code", "考试编号"),
            Map.entry("important", "是否重要考试"),
            Map.entry("secrecy", "试卷保密"),
            Map.entry("loopEnabled", "循环考试"),
            Map.entry("entryStartAt", "入场开始时间"),
            Map.entry("entryEndAt", "入场结束时间"),
            Map.entry("requiredType", "学习要求"),
            Map.entry("passScore", "通过分数"),
            Map.entry("attemptLimit", "答题次数限制"),
            Map.entry("paperId", "使用试卷"),
            Map.entry("ownerUserIds", "负责人"));

    private final ExamArrangeMapper arrangeMapper;
    private final ExamArrangeStudentMapper studentMapper;
    private final ExamChangeNoticeMapper noticeMapper;
    private final ExamNoticeOutboxMapper outboxMapper;
    private final ExamArrangeOperationLogMapper logMapper;
    private final OrgQueryService orgQueryService;
    private final NotificationPublishService notificationPublishService;
    private final CurrentUserService currentUserService;

    @Override
    public ArrangeNoticeRespDtos.Preview preview(String arrangeId, ArrangeNoticeDtos.NoticeReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        List<String> fields = fields(req);
        ArrangeNoticeRespDtos.Preview p = new ArrangeNoticeRespDtos.Preview();
        p.setTitle(resolveTitle(req));
        p.setContent(resolveContent(arrange, req, fields));
        p.setRecipientQty(countRecipients(arrangeId));
        return p;
    }

    @Override
    @Transactional
    public ArrangeNoticeRespDtos.SendResult send(String arrangeId, ArrangeNoticeDtos.NoticeReq req) {
        ExamArrange arrange = requireArrange(arrangeId);
        List<String> fields = fields(req);
        String title = resolveTitle(req);
        String content = resolveContent(arrange, req, fields);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new BizException(ERR_EMPTY, "通知标题与内容不能为空");
        }
        int recipients = countRecipients(arrangeId);
        String uid = currentUserService.userId(null);

        String noticeId = IdWorker.getIdStr();
        LocalDateTime now = LocalDateTime.now();
        ExamChangeNotice notice = new ExamChangeNotice();
        notice.setId(noticeId);
        notice.setArrangeId(arrangeId);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setChangedFields(writeFields(fields));
        notice.setStatus(0);
        notice.setRecipientQty(recipients);
        notice.setCreatedBy(uid);
        notice.setCreatedByName(resolveName(uid));
        notice.setCreatedAt(now);
        noticeMapper.insert(notice);

        ExamNoticeOutbox outbox = new ExamNoticeOutbox();
        outbox.setId(IdWorker.getIdStr());
        outbox.setArrangeId(arrangeId);
        outbox.setNoticeId(noticeId);
        outbox.setEventCode(EVENT_CODE);
        outbox.setDedupKey(dedupKey(arrangeId, noticeId));
        outbox.setPayloadJson(buildPayload(arrange, noticeId, title, content, fields, recipients));
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outboxMapper.insert(outbox);

        log(arrangeId, "change-notice", "noticeId=" + noticeId + " recipients=" + recipients);
        log.info("考试变更通知已入 outbox：arrangeId={} noticeId={} eventId={} recipients={}",
                arrangeId, noticeId, outbox.getId(), recipients);

        ArrangeNoticeRespDtos.SendResult r = new ArrangeNoticeRespDtos.SendResult();
        r.setNoticeId(noticeId);
        r.setEventId(outbox.getId());
        r.setRecipientQty(recipients);
        return r;
    }

    @Override
    @Transactional
    public void cancel(String arrangeId, String noticeId) {
        ExamChangeNotice notice = noticeId == null ? null : noticeMapper.selectById(noticeId);
        if (notice == null || !arrangeId.equals(notice.getArrangeId())) {
            throw new BizException(ERR_NOT_FOUND, "变更通知不存在或不属于该考试");
        }
        if (notice.getStatus() != null && notice.getStatus() == 3) {
            return; // 幂等：已撤销再点一次不报错
        }
        try {
            CancelNotificationCmd cmd = new CancelNotificationCmd();
            cmd.setDedupKey(dedupKey(arrangeId, noticeId));
            cmd.setReason("管理端撤销");
            notificationPublishService.cancel(cmd);
        } catch (Exception e) {
            log.warn("撤销变更通知失败：arrangeId={} noticeId={} err={}", arrangeId, noticeId, e.getMessage());
            throw new BizException(ERR_CHANNEL, "通知通道不可用，撤销失败，请稍后重试");
        }
        // 还没投出去的 outbox 直接终结，否则撤销后又被 worker 发出去
        outboxMapper.update(null, Wrappers.<ExamNoticeOutbox>lambdaUpdate()
                .eq(ExamNoticeOutbox::getNoticeId, noticeId)
                .eq(ExamNoticeOutbox::getStatus, 0)
                .set(ExamNoticeOutbox::getStatus, 3)
                .set(ExamNoticeOutbox::getErrorMessage, "管理端撤销"));
        notice.setStatus(3);
        noticeMapper.updateById(notice);
        log(arrangeId, "change-notice-cancel", "noticeId=" + noticeId);
    }

    /** 与 outbox 写入时保持同一口径，通道侧撤销要靠它定位事件。 */
    private String dedupKey(String arrangeId, String noticeId) {
        return "exam:" + EVENT_CODE + ":" + arrangeId + ":" + noticeId;
    }

    @Override
    public PageResult<ArrangeNoticeRespDtos.NoticeItem> history(String arrangeId, int current, int limit) {
        requireArrange(arrangeId);
        IPage<ExamChangeNotice> page = noticeMapper.selectPage(new Page<>(current, limit),
                Wrappers.<ExamChangeNotice>lambdaQuery()
                        .eq(ExamChangeNotice::getArrangeId, arrangeId)
                        .orderByDesc(ExamChangeNotice::getCreatedAt));
        List<ArrangeNoticeRespDtos.NoticeItem> items = new ArrayList<>();
        for (ExamChangeNotice n : page.getRecords()) {
            ArrangeNoticeRespDtos.NoticeItem it = new ArrangeNoticeRespDtos.NoticeItem();
            it.setId(n.getId());
            it.setTitle(n.getTitle());
            it.setContent(n.getContent());
            it.setChangedFields(readFields(n.getChangedFields()));
            it.setStatus(n.getStatus());
            it.setRecipientQty(n.getRecipientQty());
            it.setCreatedBy(n.getCreatedBy());
            it.setCreatedByName(n.getCreatedByName());
            it.setCreatedAt(n.getCreatedAt() == null ? null : TIME.format(n.getCreatedAt()));
            it.setSentAt(n.getSentAt() == null ? null : TIME.format(n.getSentAt()));
            items.add(it);
        }
        return PageResult.of(items, page.getTotal());
    }

    // ---------------- 内部 ----------------

    private ExamArrange requireArrange(String arrangeId) {
        ExamArrange arrange = arrangeId == null ? null : arrangeMapper.selectById(arrangeId);
        if (arrange == null) {
            throw new BizException(ERR_NOT_FOUND, "考试不存在或已删除");
        }
        return arrange;
    }

    /** 接收人 = 该考试已安排的考生数；发布前为 0。 */
    private int countRecipients(String arrangeId) {
        Long c = studentMapper.selectCount(Wrappers.<ExamArrangeStudent>lambdaQuery()
                .eq(ExamArrangeStudent::getArrangeId, arrangeId));
        return c == null ? 0 : c.intValue();
    }

    private List<String> fields(ArrangeNoticeDtos.NoticeReq req) {
        if (req == null || req.getChangedFields() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String f : req.getChangedFields()) {
            if (StringUtils.hasText(f)) {
                out.add(f.trim());
            }
        }
        return out;
    }

    private String resolveTitle(ArrangeNoticeDtos.NoticeReq req) {
        String t = req == null ? null : req.getTitle();
        return StringUtils.hasText(t) ? t.trim() : "考试信息变更通知";
    }

    private String resolveContent(ExamArrange arrange, ArrangeNoticeDtos.NoticeReq req, List<String> fields) {
        String c = req == null ? null : req.getContent();
        if (StringUtils.hasText(c)) {
            return c.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("考试「").append(arrange.getName() == null ? "" : arrange.getName()).append("」的信息发生变更");
        if (!fields.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (String f : fields) {
                labels.add(FIELD_LABELS.getOrDefault(f, f));
            }
            sb.append("：").append(String.join("、", labels));
        }
        sb.append("，请留意并按时参加考试。");
        return sb.toString();
    }

    private String writeFields(List<String> fields) {
        try {
            return JSON.writeValueAsString(fields);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readFields(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 通用通知通道的 publish 入参快照（08 §4 NotificationPublishCmd 的 JSON 形态）。 */
    private String buildPayload(ExamArrange arrange, String noticeId, String title, String content,
                                List<String> fields, int recipients) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceService", "exam");
        payload.put("eventCode", EVENT_CODE);
        payload.put("bizType", "ARRANGE");
        payload.put("bizId", arrange.getId());
        payload.put("bizName", arrange.getName());
        payload.put("noticeId", noticeId);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("changedFields", fields);
        payload.put("recipientQty", recipients);
        payload.put("recipientRule", Map.of("type", "ARRANGE_STUDENTS", "values", List.of()));
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String resolveName(String uid) {
        if (!StringUtils.hasText(uid) || "0".equals(uid)) {
            return "";
        }
        try {
            for (SysUserDTO u : orgQueryService.listUsersByIds(List.of(Long.valueOf(uid)))) {
                if (u.getName() != null) {
                    return u.getName();
                }
            }
        } catch (Exception e) {
            log.warn("解析变更通知发送人姓名失败: {}", e.getMessage());
        }
        return "";
    }

    private void log(String arrangeId, String action, String detail) {
        try {
            ExamArrangeOperationLog row = new ExamArrangeOperationLog();
            row.setId(IdWorker.getIdStr());
            row.setArrangeId(arrangeId);
            row.setAction(action);
            row.setTargetType("arrange");
            row.setTargetId("");
            row.setDetailJson(detail);
            row.setOperatorId(currentUserService.userId(null));
            row.setCreatedAt(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("写变更通知操作日志失败: {}", e.getMessage());
        }
    }
}
