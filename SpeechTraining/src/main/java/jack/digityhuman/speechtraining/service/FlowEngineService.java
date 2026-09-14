package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.common.KeyPointsUtil;
import jack.digityhuman.speechtraining.dto.resp.MessageResp;
import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.dto.resp.SessionDetailResp;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 流程引擎服务（学员端话术训练核心，FP-12/19/20/21）
 * <p>
 * 适配实际存储结构：一个大节点（type 2=陪练者问学员 / 3=学员问陪练者）为【一行】training_node 记录，
 * 同时含 trainerQuestion（陪练者问）与 standardScript/keyPoints/流转条件（学员答侧），
 * 连线直接连接大节点。流转：开始节点 → 大节点 → … → 结束节点，按出边权重随机路由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowEngineService {

    private final TrainingSessionMapper sessionMapper;
    private final TrainingSessionMessageMapper messageMapper;
    private final TrainingProjectMapper projectMapper;
    private final TrainingNodeMapper nodeMapper;
    private final TrainingEdgeMapper edgeMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final TrainingReportMapper reportMapper;
    private final DifyScoringService difyScoringService;
    private final UserContext userContext;
    private final ReportGenerationService reportGenerationService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 节点类型 */
    private static final int NODE_START = 1;
    private static final int NODE_ROUND = 2;   // 陪练者问学员（大节点）
    private static final int NODE_ROUND_ALT = 3; // 学员问陪练者（大节点，与 type2 同样处理）
    private static final int NODE_END = 4;

    /** 消息角色 */
    private static final int ROLE_TRAINER = 1;
    private static final int ROLE_STUDENT = 2;

    /** 会话状态 */
    private static final int SESSION_ONGOING = 0;
    private static final int SESSION_FINISHED = 1;
    private static final int SESSION_INCOMPLETE = 2;

    /**
     * 开始练习：创建会话，定位首个大节点，落库首个陪练者问（FP-19）
     */
    @Transactional
    public StartSessionResp start(Long projectId) {
        // 1. 项目校验：存在、已发布、在有效期内
        TrainingProject project = projectMapper.selectById(projectId);
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw new BizException("项目不存在或未发布");
        }
        LocalDateTime now = LocalDateTime.now();
        if (project.getStartTime() != null && now.isBefore(project.getStartTime())) {
            throw new BizException("项目不在有效期内");
        }
        if (project.getEndTime() != null && now.isAfter(project.getEndTime())) {
            throw new BizException("项目不在有效期内");
        }

        // 2. 加载流程并校验拓扑
        List<TrainingNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<TrainingNode>()
                .eq(TrainingNode::getProjectId, projectId));
        List<TrainingEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<TrainingEdge>()
                .eq(TrainingEdge::getProjectId, projectId));
        TrainingNode startNode = nodes.stream()
                .filter(n -> n.getNodeType() != null && n.getNodeType() == NODE_START)
                .findFirst()
                .orElseThrow(() -> new BizException("流程未配置或拓扑不完整"));
        // 总轮次 = 大节点数
        long totalRounds = nodes.stream()
                .filter(n -> n.getNodeType() != null && (n.getNodeType() == NODE_ROUND || n.getNodeType() == NODE_ROUND_ALT))
                .count();
        if (totalRounds == 0) {
            throw new BizException("流程未配置或拓扑不完整");
        }
        // 首个大节点 = 开始节点的出边目标
        TrainingNode firstRoundNode = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(startNode.getId()))
                .findFirst()
                .map(e -> nodeById(nodes, e.getTargetNodeId()))
                .orElseThrow(() -> new BizException("流程未配置或拓扑不完整"));
        if (!isRoundNode(firstRoundNode)) {
            throw new BizException("流程未配置或拓扑不完整");
        }

        // 3. 创建会话
        TrainingSession session = new TrainingSession();
        session.setProjectId(projectId);
        session.setUserId(userContext.getStudentId());
        session.setUserName(userContext.getStudentName());
        session.setStatus(SESSION_ONGOING);
        session.setReportStatus(0);
        session.setCurrentNodeId(firstRoundNode.getId());
        session.setFinishedRounds(0);
        session.setTotalRounds((int) totalRounds);
        session.setStartTime(now);
        sessionMapper.insert(session);

        // 4. 落库首个陪练者问（seq=0）
        TrainingSessionMessage firstMessage = insertMessage(session.getId(), firstRoundNode, ROLE_TRAINER,
                firstRoundNode.getTrainerQuestion(), null, null, null, 0, 1);

        // 5. 组装响应
        StartSessionResp resp = new StartSessionResp();
        resp.setSessionId(session.getId());
        resp.setTrainerName(trainerName(projectId));
        resp.setTotalRounds(session.getTotalRounds());
        resp.setFinishedRounds(0);
        resp.setMessages(List.of(toMessageResp(firstMessage)));
        return resp;
    }

    /**
     * 发送消息：Dify 要点命中判定 → 流转条件复核 → 权重路由（FP-12/20）
     */
    @Transactional
    public SendMessageResp sendMessage(Long sessionId, String content) {
        // 1. 会话与输入校验
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BizException("请输入内容");
        }
        if (text.length() > 2000) {
            throw new BizException("消息长度不能超过2000字");
        }
        TrainingSession session = requireOngoing(sessionId);
        TrainingNode currentNode = nodeMapper.selectById(session.getCurrentNodeId());
        if (currentNode == null || !isRoundNode(currentNode)) {
            throw new BizException("流程拓扑异常：当前节点不是有效的学员节点");
        }

        // 2. 解析要点与流转条件
        List<KeyPointsUtil.KeyPoint> keyPoints = KeyPointsUtil.parse(currentNode.getKeyPoints());
        List<String> keyPointContents = keyPoints.stream().map(KeyPointsUtil.KeyPoint::content).toList();

        // 3. 调 Dify 话术评分器判定要点命中（复用已有智能体）
        AiHitResult ai = callScoringAgent(currentNode, keyPoints, text);

        // 4. 后端按流转条件独立复核（不信任 LLM 自评 passed）
        boolean passed = judgePass(currentNode, keyPoints, ai.hitPoints());
        int roundNo = session.getFinishedRounds() + 1;
        int nextSeq = nextSeq(sessionId);

        // 5.1 未通过：撤回消息（withdrawn=1 留存），返回要点提示
        if (!passed) {
            insertMessage(sessionId, currentNode, ROLE_STUDENT, text, roundNo, ai.hitPointsJson(), 0, 1, nextSeq);
            return SendMessageResp.withdrawn(keyPointContents, session.getFinishedRounds(), session.getTotalRounds());
        }

        // 5.2 通过：消息保留，轮次+1
        insertMessage(sessionId, currentNode, ROLE_STUDENT, text, roundNo, ai.hitPointsJson(), 1, 0, nextSeq);
        session.setFinishedRounds(session.getFinishedRounds() + 1);

        // 6. 按出边权重随机路由
        TrainingNode next = routeByWeight(session.getProjectId(), currentNode.getId());
        if (next.getNodeType() != null && next.getNodeType() == NODE_END) {
            // 6.1 流程结束：置完成 + 同步落报告行（生成中）+ 事务提交后异步生成
            finishSession(session);
            return SendMessageResp.finished(keyPointContents, session.getFinishedRounds(), session.getTotalRounds());
        }
        if (!isRoundNode(next)) {
            throw new BizException("流程拓扑异常：下游节点类型不合法");
        }
        // 6.2 流转到下一个大节点：current_node_id 前进 + 落库陪练者问
        session.setCurrentNodeId(next.getId());
        sessionMapper.updateById(session);
        TrainingSessionMessage trainerMessage = insertMessage(sessionId, next, ROLE_TRAINER,
                next.getTrainerQuestion(), null, null, null, nextSeq + 1, session.getFinishedRounds() + 1);

        MessageResp nextMsg = toMessageResp(trainerMessage);
        return SendMessageResp.passed(keyPointContents, nextMsg, session.getFinishedRounds(), session.getTotalRounds());
    }

    /**
     * 获取会话详情（训练页刷新恢复，FP-19）
     */
    public SessionDetailResp getSessionDetail(Long sessionId) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        TrainingProject project = projectMapper.selectById(session.getProjectId());

        SessionDetailResp resp = new SessionDetailResp();
        resp.setSessionId(session.getId());
        resp.setProjectId(session.getProjectId());
        resp.setProjectName(project == null ? null : project.getName());
        resp.setStatus(session.getStatus());
        resp.setTrainerName(trainerName(session.getProjectId()));
        resp.setTotalRounds(session.getTotalRounds());
        resp.setFinishedRounds(session.getFinishedRounds());
        resp.setMessages(listMessages(sessionId).stream().map(this::toMessageResp).toList());
        return resp;
    }

    /**
     * 退出训练：会话置"未完成"，保留消息，不生成报告（FP-13/19）
     */
    @Transactional
    public void exit(Long sessionId) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        if (session.getStatus() != null && session.getStatus() != SESSION_ONGOING) {
            return; // 已结束（完成/退出），幂等处理
        }
        LocalDateTime now = LocalDateTime.now();
        session.setStatus(SESSION_INCOMPLETE);
        session.setEndTime(now);
        session.setDurationSeconds(durationSeconds(session.getStartTime(), now));
        sessionMapper.updateById(session);
    }

    // ==================== 内部方法 ====================

    /** Dify 话术评分器调用结果 */
    private record AiHitResult(List<String> hitPoints, String hitPointsJson) {
    }

    /**
     * 调用 Dify 话术评分器（微调版），返回命中要点列表与其 JSON 串
     */
    private AiHitResult callScoringAgent(TrainingNode node, List<KeyPointsUtil.KeyPoint> keyPoints, String userInput) {
        // 要点输入用纯内容数组，约束 hit_points 与输入逐字一致（微调版 prompt 约定）
        String keyPointsJson = toJson(keyPoints.stream().map(KeyPointsUtil.KeyPoint::content).toList());
        String passConditionText = buildPassConditionText(node, keyPoints);
        var ai = difyScoringService.score(node.getStandardScript(), keyPointsJson, passConditionText, userInput);
        List<String> hits = ai.getHitPoints() == null ? List.of() : ai.getHitPoints();
        return new AiHitResult(hits, toJson(hits));
    }

    /**
     * 构造流转条件描述文本（传给 Dify 评分器）
     */
    private String buildPassConditionText(TrainingNode node, List<KeyPointsUtil.KeyPoint> keyPoints) {
        Integer type = node.getPassConditionType();
        if (type != null && type == 2) {
            // 命中指定要点：按要点 ID 解析出内容
            List<String> ids = KeyPointsUtil.parsePointIds(node.getPassConditionPoints());
            List<String> contents = keyPoints.stream()
                    .filter(kp -> ids.contains(kp.id()))
                    .map(KeyPointsUtil.KeyPoint::content)
                    .toList();
            if (!contents.isEmpty()) {
                return "必须命中以下要点：" + contents;
            }
        }
        int n = node.getPassConditionN() == null ? 1 : node.getPassConditionN();
        return "命中要点列表中的任意 " + n + " 个即算通过";
    }

    /**
     * 流转条件判定（后端独立复核，不信任 LLM 自评）：
     * type=1 命中任意N个 → |命中要点∩要点列表| >= N；
     * type=2 命中指定 → 指定要点全部被命中。
     * 命中匹配：去空格等值比较，失败时做包含式兜底
     */
    boolean judgePass(TrainingNode node, List<KeyPointsUtil.KeyPoint> keyPoints, List<String> hits) {
        // 未配置要点视为无门槛，直接通过
        if (keyPoints.isEmpty()) {
            return true;
        }
        // 计算被命中的要点集合
        Set<String> matchedContents = new HashSet<>();
        for (KeyPointsUtil.KeyPoint kp : keyPoints) {
            String point = normalize(kp.content());
            if (point.isEmpty()) continue;
            for (String hit : hits) {
                String h = normalize(hit);
                if (h.isEmpty()) continue;
                // 精确等值优先，包含式兜底（Dify 输出与要点轻微出入时）
                if (point.equals(h) || point.contains(h) || h.contains(point)) {
                    matchedContents.add(kp.content());
                    break;
                }
            }
        }
        Integer type = node.getPassConditionType();
        if (type != null && type == 2) {
            List<String> ids = KeyPointsUtil.parsePointIds(node.getPassConditionPoints());
            List<String> required = keyPoints.stream()
                    .filter(kp -> ids.contains(kp.id()))
                    .map(KeyPointsUtil.KeyPoint::content)
                    .toList();
            // 指定要点为空时退化为命中任意 1 个
            if (required.isEmpty()) {
                return !matchedContents.isEmpty();
            }
            return matchedContents.containsAll(required);
        }
        int n = node.getPassConditionN() == null ? 1 : node.getPassConditionN();
        return matchedContents.size() >= n;
    }

    /**
     * 按出边权重随机路由到下游节点（可测试性：随机数集中在 nextInt 便于覆写）
     */
    TrainingNode routeByWeight(Long projectId, Long fromNodeId) {
        List<TrainingEdge> outEdges = edgeMapper.selectList(new LambdaQueryWrapper<TrainingEdge>()
                .eq(TrainingEdge::getProjectId, projectId)
                .eq(TrainingEdge::getSourceNodeId, fromNodeId));
        if (outEdges.isEmpty()) {
            throw new BizException("流程拓扑异常：当前节点没有出边，无法流转");
        }
        // 权重兜底：null/非正数按 1 处理
        int[] weights = outEdges.stream()
                .mapToInt(e -> e.getWeight() == null || e.getWeight() <= 0 ? 1 : e.getWeight())
                .toArray();
        int total = Arrays.stream(weights).sum();
        int pick = nextInt(total);
        int acc = 0;
        TrainingEdge chosen = outEdges.get(outEdges.size() - 1);
        for (int i = 0; i < outEdges.size(); i++) {
            acc += weights[i];
            if (pick < acc) {
                chosen = outEdges.get(i);
                break;
            }
        }
        TrainingNode target = nodeMapper.selectById(chosen.getTargetNodeId());
        if (target == null) {
            throw new BizException("流程拓扑异常：下游节点不存在");
        }
        return target;
    }

    /**
     * 随机数入口（单测可覆写验证 1:3 权重分布）
     */
    int nextInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    /**
     * 流程结束：会话置完成 + 插入报告行（status=生成中）+ 事务提交后异步生成报告
     */
    private void finishSession(TrainingSession session) {
        LocalDateTime now = LocalDateTime.now();
        session.setStatus(SESSION_FINISHED);
        session.setEndTime(now);
        session.setDurationSeconds(durationSeconds(session.getStartTime(), now));
        session.setReportStatus(1);
        sessionMapper.updateById(session);

        // 同步落报告行（生成中），保证前端立即轮询可见
        TrainingReport report = new TrainingReport();
        report.setSessionId(session.getId());
        report.setProjectId(session.getProjectId());
        report.setUserId(session.getUserId());
        report.setStatus(1);
        reportMapper.insert(report);

        // 事务提交后再触发异步生成，避免异步线程读不到未提交数据
        Long sessionId = session.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reportGenerationService.generateAsync(sessionId);
            }
        });
    }

    /** 校验会话存在且进行中 */
    private TrainingSession requireOngoing(Long sessionId) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        if (session.getStatus() == null || session.getStatus() != SESSION_ONGOING) {
            throw new BizException("会话已结束，不能继续发送");
        }
        return session;
    }

    /** 是否为大节点（type 2/3，需学员作答的一轮） */
    private boolean isRoundNode(TrainingNode node) {
        return node != null && node.getNodeType() != null
                && (node.getNodeType() == NODE_ROUND || node.getNodeType() == NODE_ROUND_ALT);
    }

    /** 从列表中取节点 */
    private TrainingNode nodeById(List<TrainingNode> nodes, Long id) {
        return nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElse(null);
    }

    /** 陪练者名称 */
    private String trainerName(Long projectId) {
        VirtualTrainer trainer = virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, projectId));
        return trainer == null ? null : trainer.getTrainerName();
    }

    /** 会话消息列表（按 seq 升序，含撤回） */
    private List<TrainingSessionMessage> listMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TrainingSessionMessage>()
                .eq(TrainingSessionMessage::getSessionId, sessionId)
                .orderByAsc(TrainingSessionMessage::getSeq));
    }

    /** 下一个消息序号 */
    private int nextSeq(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TrainingSessionMessage>()
                        .eq(TrainingSessionMessage::getSessionId, sessionId))
                .stream().mapToInt(m -> m.getSeq() == null ? 0 : m.getSeq())
                .max().orElse(-1) + 1;
    }

    /** 落库一条消息 */
    private TrainingSessionMessage insertMessage(Long sessionId, TrainingNode node, int role, String content,
                                                 Integer roundNo, String hitPointsJson, Integer passed,
                                                 Integer withdrawn, int seq) {
        TrainingSessionMessage msg = new TrainingSessionMessage();
        msg.setSessionId(sessionId);
        msg.setNodeId(node == null ? null : node.getId());
        msg.setRoundNo(roundNo);
        msg.setRole(role);
        msg.setContent(content);
        msg.setHitPoints(hitPointsJson);
        msg.setPassed(passed);
        msg.setWithdrawn(withdrawn);
        msg.setLikeCount(0);
        msg.setSeq(seq);
        messageMapper.insert(msg);
        return msg;
    }

    /** 实体 → 响应 */
    private MessageResp toMessageResp(TrainingSessionMessage msg) {
        MessageResp resp = new MessageResp();
        resp.setMessageId(msg.getId());
        resp.setSeq(msg.getSeq());
        resp.setRole(msg.getRole());
        resp.setContent(msg.getContent());
        resp.setPassed(msg.getPassed());
        resp.setWithdrawn(msg.getWithdrawn());
        resp.setLikeCount(msg.getLikeCount());
        return resp;
    }

    /** 练习时长（秒），开始时间缺失时为 0 */
    private int durationSeconds(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return (int) Duration.between(start, end).getSeconds();
    }

    /** 去除全部空白字符 */
    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 对象转 JSON 字符串（失败返回 "[]"） */
    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
