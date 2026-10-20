package jack.digityhuman.staffmanagement.notification.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.notification.entity.SysNoticeRecipient;
import jack.digityhuman.staffmanagement.notification.mapper.SysNoticeRecipientMapper;
import jack.digityhuman.staffmanagement.notification.service.NoticeService;
import jack.digityhuman.staffmanagement.notification.vo.NoticeItemVo;
import jack.digityhuman.staffmanagement.notification.vo.NoticeQuery;
import jack.digityhuman.staffmanagement.notification.vo.UnreadCountVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 学员端通知读接口实现（08 §5）。
 *
 * <p>所有查询都带 {@code user_id = 当前登录人}，已读接口还要再校验一次归属，
 * 避免拿到别人的 recipientId 就能改别人的已读状态。
 * 已撤销（delivered_status=3）的记录对外不可见，撤销时也已被置为已读，
 * 因此不会残留在未读数里。
 */
@Service
public class NoticeServiceImpl implements NoticeService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DELIVERED_CANCELLED = 3;

    private final SysNoticeRecipientMapper recipientMapper;

    public NoticeServiceImpl(SysNoticeRecipientMapper recipientMapper) {
        this.recipientMapper = recipientMapper;
    }

    @Override
    public PageVo<NoticeItemVo> listForUser(String userId, NoticeQuery query) {
        NoticeQuery q = query == null ? new NoticeQuery() : query;
        IPage<SysNoticeRecipient> page = recipientMapper.selectPage(
                new Page<>(q.safeCurrent(), q.safeLimit()),
                Wrappers.<SysNoticeRecipient>lambdaQuery()
                        .eq(SysNoticeRecipient::getUserId, userId)
                        .ne(SysNoticeRecipient::getDeliveredStatus, DELIVERED_CANCELLED)
                        .eq(q.getReadStatus() != null && q.getReadStatus() >= 0,
                                SysNoticeRecipient::getReadStatus, q.getReadStatus())
                        .eq(StringUtils.hasText(q.getSourceService()),
                                SysNoticeRecipient::getSourceService, q.getSourceService())
                        .orderByDesc(SysNoticeRecipient::getCreatedAt));
        List<NoticeItemVo> items = new ArrayList<>();
        for (SysNoticeRecipient row : page.getRecords()) {
            items.add(toVo(row));
        }
        return PageVo.of(items, page.getTotal(), q.safeCurrent(), q.safeLimit());
    }

    @Override
    public UnreadCountVo unreadCount(String userId) {
        Long n = recipientMapper.selectCount(Wrappers.<SysNoticeRecipient>lambdaQuery()
                .eq(SysNoticeRecipient::getUserId, userId)
                .eq(SysNoticeRecipient::getDeliveredStatus, 1)
                .eq(SysNoticeRecipient::getReadStatus, 0));
        return UnreadCountVo.of(n == null ? 0 : n);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String userId, String recipientId) {
        SysNoticeRecipient row = recipientId == null ? null : recipientMapper.selectById(recipientId);
        if (row == null) {
            throw BizException.notFound("通知 " + recipientId);
        }
        if (!userId.equals(row.getUserId())) {
            // 刻意用"不存在"口径，避免暴露他人通知 id 的有效性
            throw BizException.notFound("通知 " + recipientId);
        }
        if (row.getReadStatus() != null && row.getReadStatus() == 1) {
            return; // 幂等：已读再点一次不报错
        }
        row.setReadStatus(1);
        row.setReadAt(LocalDateTime.now());
        recipientMapper.updateById(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(String userId, String sourceService) {
        return recipientMapper.update(null, Wrappers.<SysNoticeRecipient>lambdaUpdate()
                .eq(SysNoticeRecipient::getUserId, userId)
                .eq(SysNoticeRecipient::getReadStatus, 0)
                .eq(SysNoticeRecipient::getDeliveredStatus, 1)
                .eq(StringUtils.hasText(sourceService), SysNoticeRecipient::getSourceService, sourceService)
                .set(SysNoticeRecipient::getReadStatus, 1)
                .set(SysNoticeRecipient::getReadAt, LocalDateTime.now()));
    }

    private NoticeItemVo toVo(SysNoticeRecipient row) {
        NoticeItemVo vo = new NoticeItemVo();
        vo.setRecipientId(row.getId());
        vo.setEventId(row.getEventId());
        vo.setSourceService(row.getSourceService());
        vo.setBizType(row.getBizType());
        vo.setBizId(row.getBizId());
        vo.setTitle(row.getTitle());
        vo.setContent(row.getContent());
        vo.setReadStatus(row.getReadStatus());
        vo.setReadAt(row.getReadAt() == null ? null : TIME.format(row.getReadAt()));
        vo.setCreatedAt(row.getCreatedAt() == null ? null : TIME.format(row.getCreatedAt()));
        return vo;
    }
}
