package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.KeyPointsUtil;
import jack.digityhuman.speechtraining.dify.DifyScoringService;
import jack.digityhuman.speechtraining.dto.resp.MessageResp;
import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.dto.resp.SessionDetailResp;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.dto.resp.VoiceMessageResp;
import jack.digityhuman.speechtraining.entity.*;
import jack.digityhuman.speechtraining.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 流程引擎服务实现（学员端话术训练核心，FP-12/19/20/21）。
 * <p>
 * 适配实际存储结构：一个大节点（type 2=陪练者问学员 / 3=学员问陪练者）为【一行】training_node 记录，
 * 同时含 trainerQuestion（陪练者问）与 standardScript/keyPoints/流转条件（学员答侧），
 * 连线直接连接大节点。流转：开始节点 → 大节点 → … → 结束节点，按出边权重随机路由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowEngineServiceImpl implements FlowEngineService {

    private final TrainingSessionMapper sessionMapper;
    private final TrainingSessionMessageMapper messageMapper;
    private final TrainingProjectMapper projectMapper;
    private final TrainingNodeMapper nodeMapper;
    private final TrainingEdgeMapper edgeMapper;
    private final VirtualTrainerMapper virtualTrainerMapper;
    private final TrainingReportMapper reportMapper;
    private final TrainingRoundAcousticMapper acousticMapper;
    private final DifyScoringService difyScoringService;
    private final UserContext userContext;
    private final ReportGenerationService reportGenerationService;
    private final jack.digityhuman.speechtraining.common.FileStorageService fileStorageService;
    /** 写阶段短事务：判定（含 Dify 调用）在事务外完成，落库+流转在事务内持行锁完成 */
    private final TransactionTemplate transactionTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 包含式命中匹配所要求的命中项最短长度。
     * <p>Dify 只回一个"好"字之类时，不足以证明它命中了整条要点；但要点本身就这么短时，
     * 逐字等值仍然算命中（等值说明是模型自己判定命中的，不是后端字符串匹配放的水）。
     */
    private static final int MIN_CONTAIN_HIT_LEN = 2;

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
    @Override
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
        // 首个大节点 = 开始节点的出边，同样按权重随机路由
        //（开始节点可以有多条出边=多个开场分支；按 id 取第一条会让权重配置在开局失效）
        TrainingNode firstRoundNode = routeByWeight(projectId, startNode.getId());
        if (!isRoundNode(firstRoundNode)) {
            throw new BizException("流程未配置或拓扑不完整：开始节点的下游不是学员节点");
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

        // 4. 落库首个陪练者问（seq=0，属于第 1 轮）
        TrainingSessionMessage firstMessage = insertMessage(session.getId(), firstRoundNode, ROLE_TRAINER,
                firstRoundNode.getTrainerQuestion(), 1, null, null, 0, 0, null);

        // 5. 组装响应（v1.1 增加 dialogueMode/trainerAvatar）
        VirtualTrainer trainer = trainerOf(projectId);
        StartSessionResp resp = new StartSessionResp();
        resp.setSessionId(session.getId());
        resp.setTrainerName(trainer == null ? null : trainer.getTrainerName());
        resp.setTrainerAvatar(trainer == null ? null : trainer.getAvatarImage());
        resp.setDialogueMode(project.getDialogueMode() == null ? 0 : project.getDialogueMode());
        resp.setTotalRounds(session.getTotalRounds());
        resp.setFinishedRounds(0);
        resp.setMessages(List.of(toMessageResp(firstMessage)));
        return resp;
    }

    /**
     * 发送消息（文字模式）：读上下文 → Dify 判定（事务外）→ 行锁内落库流转（FP-12/20）
     * <p>不加 {@code @Transactional}：Dify 一次调用可达数秒，包在事务里会让会话行长时间持锁，
     * 拖慢同一学员的重复提交与其它会话的写；改为判定无事务、只有写阶段持锁。
     */
    @Override
    public SendMessageResp sendMessage(Long sessionId, String content) {
        // 1. 输入校验
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BizException("请输入内容");
        }
        if (text.length() > 2000) {
            throw new BizException("消息长度不能超过2000字");
        }
        // 2. 读阶段：会话/当前节点/要点（无锁）
        JudgeContext ctx = readContext(sessionId);
        // 3. 判定阶段：Dify + 后端复核（无锁、无事务）
        Verdict verdict = judge(ctx, text);
        // 4. 写阶段：行锁内复核未过期后落库流转
        return apply(ctx, text, null, null, verdict).resp();
    }

    /**
     * 发送语音消息（v1.1，FP-24/28；v1.2 双输入改造）：ASR 识别文本 → 与文字模式完全相同的流转判定。
     * <p>v1.2：语音输入不再限对话方式，语音对话与文字对话项目通用。
     * <p>v1.2 修正：识别文本为空（没有听到内容）才短路，单字照常送 Dify 判定。
     * "没听到"与"没答到点上"是两回事，前者提示重说、不弹要点弹窗。
     * <p>v1.2 新增：识别结果自带的声学特征（发声时长/停顿/逐字时间戳/检出语种）随消息落
     * {@code training_round_acoustic}，撤回的回答同样留存。
     *
     * @param asr ASR 识别结果（文本 + 语音时长 + 声学特征）
     * @param wav 学员录音原始字节（WAV），落盘为 /audio/asr/{messageId}.wav
     * @return 语音消息响应（识别文本 + 音频信息 + 判定结果）
     */
    @Override
    public VoiceMessageResp sendVoiceMessage(Long sessionId, SpeechService.AsrResult asr, byte[] wav) {
        String text = asr.text() == null ? "" : asr.text().trim();
        VoiceFields voice = new VoiceFields((int) asr.durationMs(), asr.acoustic());

        JudgeContext ctx = readContext(sessionId);
        // 识别不到任何内容：不调 Dify（空文本送过去只会让模型自由发挥），直接按"没听到"撤回
        Verdict verdict = text.isEmpty() ? Verdict.NO_SPEECH : judge(ctx, text);
        SendOutcome outcome = apply(ctx, text, voice, wav, verdict);
        return VoiceMessageResp.of(outcome.resp(), text, outcome.studentMessage());
    }

    /**
     * 获取会话详情（训练页刷新恢复，FP-19；v1.1 增加 dialogueMode/trainerAvatar）
     */
    @Override
    public SessionDetailResp getSessionDetail(Long sessionId) {
        TrainingSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        TrainingProject project = projectMapper.selectById(session.getProjectId());
        VirtualTrainer trainer = trainerOf(session.getProjectId());

        SessionDetailResp resp = new SessionDetailResp();
        resp.setSessionId(session.getId());
        resp.setProjectId(session.getProjectId());
        resp.setProjectName(project == null ? null : project.getName());
        resp.setStatus(session.getStatus());
        resp.setDialogueMode(project == null || project.getDialogueMode() == null ? 0 : project.getDialogueMode());
        resp.setTrainerName(trainer == null ? null : trainer.getTrainerName());
        resp.setTrainerAvatar(trainer == null ? null : trainer.getAvatarImage());
        resp.setTotalRounds(session.getTotalRounds());
        resp.setFinishedRounds(session.getFinishedRounds());
        resp.setMessages(listMessages(sessionId).stream().map(this::toMessageResp).toList());
        return resp;
    }

    /**
     * 退出训练：会话置"未完成"，保留消息，不生成报告（FP-13/19）
     */
    @Override
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

    /** 学员消息的语音上下文（文字消息为 null） */
    private record VoiceFields(Integer audioDurationMs, SpeechService.Acoustic acoustic) {
    }

    /** 共用判定结果：流转判定响应 + 学员消息实体（语音消息用于回填 audio_url 与声学特征行） */
    private record SendOutcome(SendMessageResp resp, TrainingSessionMessage studentMessage) {
    }

    /**
     * 落盘学员录音与声学特征（两者都挂在消息ID上）：
     * 音频文件名 = 消息ID（/audio/asr/{messageId}.wav），特征行 {@code message_id} 唯一。
     */
    private void persistVoice(TrainingSessionMessage studentMessage, byte[] wav, VoiceFields voice, int roundNo) {
        if (wav != null && wav.length > 0) {
            String url = fileStorageService.saveAsrAudio(studentMessage.getId(), wav);
            studentMessage.setAudioUrl(url);
            messageMapper.updateById(studentMessage);
        }
        SpeechService.Acoustic acoustic = voice == null ? null : voice.acoustic();
        if (acoustic == null) {
            return;
        }
        TrainingRoundAcoustic row = new TrainingRoundAcoustic();
        row.setSessionId(studentMessage.getSessionId());
        row.setMessageId(studentMessage.getId());
        row.setRoundNo(roundNo);
        row.setAudioMs(acoustic.audioMs());
        row.setSpeechMs(acoustic.speechMs());
        row.setUtteranceCount(acoustic.utteranceCount());
        row.setPauseCount(acoustic.pauseCount());
        row.setPauseTotalMs(acoustic.pauseTotalMs());
        row.setMaxPauseMs(acoustic.maxPauseMs());
        row.setTextLength(acoustic.textLength());
        row.setLidLang(acoustic.lidLang());
        row.setDefinite(acoustic.definite());
        row.setWordsJson(acoustic.wordsJson());
        row.setServerAudioMs(acoustic.serverAudioMs());
        acousticMapper.insert(row);
    }

    /**
     * 校验会话当前节点为有效学员节点，返回该节点
     */
    private TrainingNode requireCurrentRoundNode(TrainingSession session) {
        TrainingNode currentNode = nodeMapper.selectById(session.getCurrentNodeId());
        if (currentNode == null || !isRoundNode(currentNode)) {
            throw new BizException("流程拓扑异常：当前节点不是有效的学员节点");
        }
        return currentNode;
    }

    /** 读阶段上下文：判定所必需的只读数据；写阶段在行锁内复核 current_node_id 未变 */
    private record JudgeContext(Long sessionId, TrainingNode node, List<KeyPointsUtil.KeyPoint> keyPoints) {
    }

    /** 判定阶段结果（Dify 命中要点 + 后端复核结论，尚未落库） */
    private record Verdict(List<String> hitPoints, String hitPointsJson, boolean passed, boolean noSpeech) {

        /** 语音未识别到内容：不调 Dify 的固定结论 */
        static final Verdict NO_SPEECH = new Verdict(List.of(), "[]", false, true);
    }

    /**
     * 读阶段：校验会话进行中、当前是学员节点，并解析要点。不开事务、不加锁。
     */
    private JudgeContext readContext(Long sessionId) {
        TrainingSession session = requireOngoing(sessionId);
        TrainingNode node = requireCurrentRoundNode(session);
        return new JudgeContext(sessionId, node, KeyPointsUtil.parse(node.getKeyPoints()));
    }

    /**
     * 判定阶段：Dify 要点命中 + 后端按流转条件独立复核（不信任 LLM 自评 passed）。
     * <p>Dify 一次调用可达数秒，因此与写阶段分离：判定期间不持有任何数据库锁。
     */
    private Verdict judge(JudgeContext ctx, String text) {
        AiHitResult ai = callScoringAgent(ctx.node(), ctx.keyPoints(), text);
        boolean passed = judgePass(ctx.node(), ctx.keyPoints(), ai.hitPoints());
        return new Verdict(ai.hitPoints(), ai.hitPointsJson(), passed, false);
    }

    /**
     * 写阶段入口：开一个只包住落库与流转的短事务。
     */
    private SendOutcome apply(JudgeContext ctx, String text, VoiceFields voice, byte[] wav, Verdict verdict) {
        return transactionTemplate.execute(status -> write(ctx, text, voice, wav, verdict));
    }

    /**
     * 写阶段实体（事务内、会话行排他锁持有期间执行）。
     * <p>锁内必须复核两件事，否则"双击提交 / 双开页面 / 判定期间已退出"会让过期回答生效：
     * ① 会话仍为进行中；② current_node_id 仍是判定所依据的那个节点。
     * 消息序号也只能在锁内计算，否则并发请求会取到同一个 seq。
     */
    private SendOutcome write(JudgeContext ctx, String text, VoiceFields voice, byte[] wav, Verdict verdict) {
        TrainingSession session = sessionMapper.selectForUpdate(ctx.sessionId());
        if (session == null) {
            throw new BizException("会话不存在");
        }
        if (session.getStatus() == null || session.getStatus() != SESSION_ONGOING) {
            throw new BizException("会话已结束，不能继续发送");
        }
        if (!Objects.equals(session.getCurrentNodeId(), ctx.node().getId())) {
            throw new BizException("本条回答已过期，请重新发送");
        }

        Long sessionId = session.getId();
        TrainingNode currentNode = ctx.node();
        List<String> keyPointContents = ctx.keyPoints().stream().map(KeyPointsUtil.KeyPoint::content).toList();
        int roundNo = session.getFinishedRounds() + 1;
        int nextSeq = nextSeq(sessionId);

        // 1. 没听到内容：撤回 + 提示重说（不带要点，此时学员还没"作答"，弹要点只会让人以为答偏了）
        if (verdict.noSpeech()) {
            TrainingSessionMessage msg = insertMessage(sessionId, currentNode, ROLE_STUDENT, text,
                    roundNo, verdict.hitPointsJson(), 0, 1, nextSeq, voice);
            persistVoice(msg, wav, voice, roundNo);
            return new SendOutcome(SendMessageResp.noSpeech(session.getFinishedRounds(),
                    session.getTotalRounds()), msg);
        }

        // 2. 未通过：撤回消息（withdrawn=1 留存，供报告与复盘使用），返回要点提示
        if (!verdict.passed()) {
            TrainingSessionMessage msg = insertMessage(sessionId, currentNode, ROLE_STUDENT, text,
                    roundNo, verdict.hitPointsJson(), 0, 1, nextSeq, voice);
            persistVoice(msg, wav, voice, roundNo);
            return new SendOutcome(SendMessageResp.withdrawn(keyPointContents,
                    session.getFinishedRounds(), session.getTotalRounds()), msg);
        }

        // 3. 通过：消息保留，轮次+1
        TrainingSessionMessage studentMessage = insertMessage(sessionId, currentNode, ROLE_STUDENT, text,
                roundNo, verdict.hitPointsJson(), 1, 0, nextSeq, voice);
        persistVoice(studentMessage, wav, voice, roundNo);
        session.setFinishedRounds(session.getFinishedRounds() + 1);

        // 4. 按出边权重随机路由
        TrainingNode next = routeByWeight(session.getProjectId(), currentNode.getId());
        if (next.getNodeType() != null && next.getNodeType() == NODE_END) {
            // 4.1 流程结束：置完成 + 同步落报告行（生成中）+ 事务提交后异步生成
            finishSession(session);
            return new SendOutcome(SendMessageResp.finished(keyPointContents,
                    session.getFinishedRounds(), session.getTotalRounds()), studentMessage);
        }
        if (!isRoundNode(next)) {
            throw new BizException("流程拓扑异常：下游节点类型不合法");
        }
        // 4.2 流转到下一个大节点：current_node_id 前进 + 落库陪练者问
        //（roundNo=即将进行的轮次 finishedRounds+1；withdrawn=0；seq=nextSeq+1）
        session.setCurrentNodeId(next.getId());
        sessionMapper.updateById(session);
        TrainingSessionMessage trainerMessage = insertMessage(sessionId, next, ROLE_TRAINER,
                next.getTrainerQuestion(), session.getFinishedRounds() + 1, null, null, 0, nextSeq + 1, null);

        MessageResp nextMsg = toMessageResp(trainerMessage);
        return new SendOutcome(SendMessageResp.passed(keyPointContents, nextMsg,
                session.getFinishedRounds(), session.getTotalRounds()), studentMessage);
    }

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
     * type=1 命中任意N个 → 命中的要点数 >= N；
     * type=2 命中指定 → 指定要点全部被命中。
     * <p>命中匹配只认"命中项包含要点全文"这一个方向。反方向（要点包含命中项）等于
     * 学员只说了要点半句也算命中，会让长要点形同虚设；按要点下标记命中而不是按内容，
     * 是为了让内容重复的两条要点各算一次，不至于互相顶替。
     */
    boolean judgePass(TrainingNode node, List<KeyPointsUtil.KeyPoint> keyPoints, List<String> hits) {
        // 未配置要点视为无门槛：发布校验会拦住这种节点，此处只兜底历史数据，避免学员卡死
        if (keyPoints.isEmpty()) {
            log.warn("节点未配置要点，流转判定按无门槛放行, nodeId={}", node.getId());
            return true;
        }
        List<String> normHits = hits.stream().map(this::normalize).filter(h -> !h.isEmpty()).toList();
        Set<Integer> matched = new HashSet<>();
        for (int i = 0; i < keyPoints.size(); i++) {
            String point = normalize(keyPoints.get(i).content());
            if (point.isEmpty()) {
                continue;
            }
            boolean hit = normHits.stream().anyMatch(h -> h.equals(point)
                    || (h.length() >= MIN_CONTAIN_HIT_LEN && h.contains(point)));
            if (hit) {
                matched.add(i);
            }
        }
        Integer type = node.getPassConditionType();
        if (type != null && type == 2) {
            List<String> ids = KeyPointsUtil.parsePointIds(node.getPassConditionPoints());
            List<Integer> required = new ArrayList<>();
            for (int i = 0; i < keyPoints.size(); i++) {
                if (ids.contains(keyPoints.get(i).id())) {
                    required.add(i);
                }
            }
            // 指定要点为空时退化为命中任意 1 个
            if (required.isEmpty()) {
                return !matched.isEmpty();
            }
            return matched.containsAll(required);
        }
        int n = node.getPassConditionN() == null ? 1 : node.getPassConditionN();
        return matched.size() >= n;
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

        // 事务提交后再触发异步生成，避免异步线程读不到未提交数据。
        // 提交动作本身可能失败（reportExecutor 队列满抛 TaskRejectedException），此时报告行已落库为
        // "生成中"且事务已提交，若不显式置失败，学员端会永远停在"报告生成中"且拿不到重试入口
        Long sessionId = session.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    reportGenerationService.generateAsync(sessionId);
                } catch (Exception e) {
                    log.error("报告生成任务提交失败, sessionId={}", sessionId, e);
                    reportGenerationService.markFailedBySession(sessionId);
                }
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

    /** 陪练者设置（含名称与形象） */
    private VirtualTrainer trainerOf(Long projectId) {
        return virtualTrainerMapper.selectOne(
                new LambdaQueryWrapper<VirtualTrainer>().eq(VirtualTrainer::getProjectId, projectId));
    }

    /** 陪练者名称 */
    private String trainerName(Long projectId) {
        VirtualTrainer trainer = trainerOf(projectId);
        return trainer == null ? null : trainer.getTrainerName();
    }

    /** 会话消息列表（按 seq 升序，含撤回） */
    private List<TrainingSessionMessage> listMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TrainingSessionMessage>()
                .eq(TrainingSessionMessage::getSessionId, sessionId)
                .orderByAsc(TrainingSessionMessage::getSeq));
    }

    /**
     * 下一个消息序号（在会话行锁内调用，故与并发的另一个请求互斥）。
     * <p>库里 training_session_message(session_id, seq) 建了唯一索引，序号重复会直接报错而不是静默乱序。
     */
    private int nextSeq(Long sessionId) {
        return messageMapper.selectMaxSeq(sessionId) + 1;
    }

    /**
     * 落库一条消息
     *
     * @param voice 语音上下文（null=文字消息，inputMode=1；非 null=语音消息，inputMode=2）
     */
    private TrainingSessionMessage insertMessage(Long sessionId, TrainingNode node, int role, String content,
                                                 Integer roundNo, String hitPointsJson, Integer passed,
                                                 Integer withdrawn, int seq, VoiceFields voice) {
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
        // v1.1 语音字段：语音消息带时长，audio_url 与声学特征行由 persistVoice 落盘后回填
        msg.setInputMode(voice == null ? 1 : 2);
        if (voice != null) {
            msg.setAudioDurationMs(voice.audioDurationMs());
        }
        messageMapper.insert(msg);
        return msg;
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

    /**
     * 练习时长（秒）。起止时间都取应用时钟，故不受数据库时区影响。
     * <p>结束时早于开始只可能是 JVM 与数据库时钟/时区不一致（start_time 由上一次请求写入），
     * 静默返回 0 会让报告里的时长莫名其妙，故留一条 warn。
     */
    private int durationSeconds(LocalDateTime start, LocalDateTime end) {
        if (start == null) {
            return 0;
        }
        if (end == null || end.isBefore(start)) {
            log.warn("会话结束时间不晚于开始时间，时长按 0 计, start={}, end={}", start, end);
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
