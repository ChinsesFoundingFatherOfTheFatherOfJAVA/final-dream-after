package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.KeyPointsUtil;
import jack.digityhuman.speechtraining.dto.resp.ConsistencyResp;
import jack.digityhuman.speechtraining.dto.resp.MessageResp;
import jack.digityhuman.speechtraining.dto.resp.ReportResp;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学员端报告查询服务（FP-16/17/22）
 * 报告查询（轮询）/ 话术一致性详情 / 对话记录 / 消息点赞
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentReportService {

    private final TrainingReportMapper reportMapper;
    private final TrainingReportDimensionMapper reportDimensionMapper;
    private final TrainingSessionMapper sessionMapper;
    private final TrainingSessionMessageMapper messageMapper;
    private final TrainingProjectMapper projectMapper;
    private final TrainingNodeMapper nodeMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final TrainingMessageLikeMapper messageLikeMapper;
    private final UserContext userContext;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 按会话查询训练报告（报告页主接口，含生成状态轮询，FP-22）
     */
    public ReportResp getReportBySession(Long sessionId) {
        TrainingReport report = reportMapper.selectOne(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getSessionId, sessionId));
        if (report == null) {
            throw new BizException("报告不存在");
        }

        ReportResp resp = new ReportResp();
        resp.setReportId(report.getId());
        resp.setSessionId(report.getSessionId());
        resp.setReportStatus(report.getStatus());
        // 生成中/失败：仅返回状态，前端渲染等待页/失败重试
        if (report.getStatus() == null || report.getStatus() != 2) {
            return resp;
        }

        TrainingSession session = sessionMapper.selectById(report.getSessionId());
        TrainingProject project = projectMapper.selectById(report.getProjectId());

        resp.setProjectName(project == null ? null : project.getName());
        resp.setProjectType(project == null ? null : project.getProjectType());
        resp.setStudentName(session == null ? null : session.getUserName());
        // 形象与训练页左栏同源（virtual_trainer），报告页对话记录不再另画一张内置脸
        VirtualTrainer trainer = report.getProjectId() == null ? null : virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, report.getProjectId()));
        resp.setTrainerAvatar(trainer == null ? null : trainer.getAvatarImage());
        resp.setTotalScore(report.getTotalScore());
        resp.setRankNo(report.getRankNo());
        resp.setExceedPercent(report.getExceedPercent());
        resp.setScoreChange(report.getScoreChange());
        resp.setDurationSeconds(report.getDurationSeconds());
        resp.setRoundCount(report.getRoundCount());
        resp.setDimensions(listDimensions(report.getId()));
        resp.setLastDimensions(findLastDimensions(report));
        resp.setRoundSuggestions(parseRoundSuggestions(report.getRoundSuggestions()));
        return resp;
    }

    /**
     * 话术一致性详情：逐轮要点命中明细（FP-16）
     */
    public ConsistencyResp getConsistency(Long reportId) {
        TrainingReport report = requireReport(reportId);
        List<TrainingSessionMessage> messages = sessionMessages(report.getSessionId());

        ConsistencyResp resp = new ConsistencyResp();
        List<ConsistencyResp.RoundItem> rounds = new ArrayList<>();
        String pendingQuestion = null;
        Long pendingNodeId = null;
        Map<Long, TrainingNode> nodeCache = new HashMap<>();
        for (TrainingSessionMessage msg : messages) {
            if (msg.getRole() == 1) {
                if (msg.getWithdrawn() == null || msg.getWithdrawn() == 0) {
                    pendingQuestion = msg.getContent();
                    pendingNodeId = msg.getNodeId();
                }
            } else if (msg.getPassed() != null && msg.getPassed() == 1
                    && msg.getWithdrawn() != null && msg.getWithdrawn() == 0) {
                // 通过且未撤回的学员答 → 一轮
                ConsistencyResp.RoundItem round = new ConsistencyResp.RoundItem();
                round.setSeq(msg.getRoundNo());
                round.setTrainerQuestion(pendingQuestion);
                round.setStudentAnswer(msg.getContent());
                round.setKeyPoints(buildHitDetails(nodeCache, pendingNodeId, msg.getHitPoints()));
                rounds.add(round);
            }
        }
        resp.setRounds(rounds);
        return resp;
    }

    /**
     * 对话记录：只返回 withdrawn=0 的消息（FP-17）
     */
    public List<MessageResp> getSessionMessages(Long sessionId) {
        return sessionMessages(sessionId).stream()
                .filter(m -> m.getWithdrawn() == null || m.getWithdrawn() == 0)
                .map(this::toMessageResp)
                .toList();
    }

    /**
     * 消息点赞：+1，同一学员对同一消息重复点赞幂等（FP-17）
     */
    @Transactional
    public int likeMessage(Long messageId) {
        TrainingSessionMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BizException("消息不存在");
        }
        String userId = userContext.getStudentId();
        // 幂等：已点赞过则直接返回当前计数
        Long liked = messageLikeMapper.selectCount(new LambdaQueryWrapper<TrainingMessageLike>()
                .eq(TrainingMessageLike::getMessageId, messageId)
                .eq(TrainingMessageLike::getUserId, userId));
        if (liked != null && liked > 0) {
            return message.getLikeCount() == null ? 0 : message.getLikeCount();
        }
        TrainingMessageLike like = new TrainingMessageLike();
        like.setMessageId(messageId);
        like.setUserId(userId);
        messageLikeMapper.insert(like);

        int newCount = (message.getLikeCount() == null ? 0 : message.getLikeCount()) + 1;
        message.setLikeCount(newCount);
        messageMapper.updateById(message);
        return newCount;
    }

    // ==================== 内部方法 ====================

    /** 报告必须存在 */
    private TrainingReport requireReport(Long reportId) {
        TrainingReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException("报告不存在");
        }
        return report;
    }

    /** 报告维度明细列表 */
    private List<ReportResp.DimensionItem> listDimensions(Long reportId) {
        return reportDimensionMapper.selectList(new LambdaQueryWrapper<TrainingReportDimension>()
                        .eq(TrainingReportDimension::getReportId, reportId)
                        .orderByAsc(TrainingReportDimension::getSortOrder))
                .stream().map(d -> {
                    ReportResp.DimensionItem item = new ReportResp.DimensionItem();
                    item.setDimensionName(d.getDimensionName());
                    item.setWeight(d.getWeight());
                    item.setScore(d.getScore());
                    item.setComment(d.getComment());
                    item.setGoodPoints(parseStringList(d.getGoodPoints()));
                    item.setImprovements(parseStringList(d.getImprovements()));
                    item.setSortOrder(d.getSortOrder());
                    return item;
                }).toList();
    }

    /**
     * 上次报告各维度分（雷达图虚线）：同项目同学员、生成时间早于当前报告的最近一份
     */
    private List<ReportResp.LastDimensionItem> findLastDimensions(TrainingReport current) {
        List<TrainingReport> previous = reportMapper.selectList(new LambdaQueryWrapper<TrainingReport>()
                .eq(TrainingReport::getProjectId, current.getProjectId())
                .eq(TrainingReport::getUserId, current.getUserId())
                .eq(TrainingReport::getStatus, 2)
                .ne(TrainingReport::getId, current.getId())
                .lt(TrainingReport::getCreatedAt, current.getCreatedAt())
                .orderByDesc(TrainingReport::getCreatedAt)
                .last("LIMIT 1"));
        if (previous.isEmpty()) {
            return null;
        }
        return reportDimensionMapper.selectList(new LambdaQueryWrapper<TrainingReportDimension>()
                        .eq(TrainingReportDimension::getReportId, previous.get(0).getId()))
                .stream().map(d -> {
                    ReportResp.LastDimensionItem item = new ReportResp.LastDimensionItem();
                    item.setDimensionName(d.getDimensionName());
                    item.setScore(d.getScore());
                    return item;
                }).toList();
    }

    /** 会话消息（按 seq 升序，含撤回） */
    private List<TrainingSessionMessage> sessionMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TrainingSessionMessage>()
                .eq(TrainingSessionMessage::getSessionId, sessionId)
                .orderByAsc(TrainingSessionMessage::getSeq));
    }

    /** 单轮要点命中明细（nodeCache：方法内局部缓存，避免跨请求驻留） */
    private List<ConsistencyResp.KeyPointItem> buildHitDetails(Map<Long, TrainingNode> nodeCache,
                                                               Long nodeId, String hitPointsJson) {
        List<KeyPointsUtil.KeyPoint> keyPoints = nodeId == null ? List.of()
                : KeyPointsUtil.parse(nodeCache.computeIfAbsent(nodeId, id -> nodeMapper.selectById(id)).getKeyPoints());
        List<String> hits = parseStringList(hitPointsJson);
        return keyPoints.stream().map(kp -> {
            ConsistencyResp.KeyPointItem item = new ConsistencyResp.KeyPointItem();
            item.setContent(kp.content());
            item.setHit(hits.stream().anyMatch(h -> h != null && h.trim().equals(kp.content().trim())));
            return item;
        }).toList();
    }

    /** 实体 → 响应（v1.1 附带音频字段） */
    private MessageResp toMessageResp(TrainingSessionMessage msg) {
        MessageResp resp = new MessageResp();
        resp.setMessageId(msg.getId());
        resp.setSeq(msg.getSeq());
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setPassed(msg.getPassed());
        resp.setWithdrawn(msg.getWithdrawn());
        resp.setLikeCount(msg.getLikeCount());
        resp.setAudioUrl(msg.getAudioUrl());
        resp.setAudioDurationMs(msg.getAudioDurationMs());
        resp.setInputMode(msg.getInputMode() == null ? 1 : msg.getInputMode());
        return resp;
    }

    /** JSON 数组字符串 → List<String>（容错） */
    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 逐轮建议 JSON → List */
    private List<ReportResp.RoundSuggestionItem> parseRoundSuggestions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, ReportResp.RoundSuggestionItem.class));
        } catch (Exception e) {
            log.warn("解析逐轮改进建议失败: {}", json, e);
            return List.of();
        }
    }
}
