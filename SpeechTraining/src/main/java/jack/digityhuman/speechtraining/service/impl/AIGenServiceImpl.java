package jack.digityhuman.speechtraining.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.dto.req.AiGenDtos;
import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;
import jack.digityhuman.speechtraining.dify.DifyWorkflowSupport;
import jack.digityhuman.speechtraining.entity.SparringAiGenRecord;
import jack.digityhuman.speechtraining.mapper.SparringAiGenRecordMapper;
import jack.digityhuman.speechtraining.service.AIGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI生成话术闭环三步服务实现（v2.0）。
 *
 * <p>三步各自独立调用 Dify 工作流（agent：gen-scene / gen-outline / gen-script，key 在
 * dify.agents.* 配置），中间产物每步落 sparring_ai_gen_record 一条（同 session+step 删旧插新）。
 * 步骤一致性：第二步起必传上一步 result 的 SHA-256 摘要，与留痕比对不符返回
 * "场景信息已变更，请重新生成"（02 §7：防"改了场景忘改大纲"的中间态错配）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIGenServiceImpl implements AIGenService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DifyWorkflowSupport difyWorkflowSupport;
    private final SparringAiGenRecordMapper recordMapper;

    /** SG-04 场景生成：description + firstSpeaker + materials → 工作流①五字段。 */
    @Override
    public AiGenRespDtos.GenSceneResp genScene(AiGenDtos.GenSceneReq req) {
        validateSession(req.getSessionId());
        if (req.getDescription() == null || req.getDescription().isBlank()) {
            throw new BizException("请描述想要生成的话术内容");
        }
        long start = System.currentTimeMillis();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("description", req.getDescription());
        inputs.put("firstSpeaker", normalizeSpeaker(req.getFirstSpeaker()));
        inputs.put("materialsText", joinMaterialsText(req.getMaterials()));

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("gen-scene", inputs);
        AiGenRespDtos.GenSceneResp resp = readScene(readStructured(outputs));

        // 留痕：step=1（request_hash=场景出参五字段的 canonical 摘要——前端 sha256OfScene 同构对齐；
        // 03 §1 sceneHash 语义=场景数据的摘要，此前误存入参摘要是口径错误，E2E 联调修正 10-08）
        // sceneOnly 已是前端同构 JSON 字符串——直接摘要，不能再套 canonical（String 会被再转义）
        saveRecord(req.getSessionId(), SparringAiGenRecord.STEP_SCENE,
                sha256(sceneOnly(resp)),
                canonical(req), canonical(resp));
        resp.setElapsedMs(System.currentTimeMillis() - start);
        return resp;
    }

    /** SG-05 大纲生成：场景（可含用户编辑）→ 工作流②五环节。 */
    @Override
    public AiGenRespDtos.GenOutlineResp genOutline(AiGenDtos.GenOutlineReq req) {
        validateSession(req.getSessionId());
        if (req.getScene() == null || req.getScene().getScene() == null
                || req.getScene().getScene().isBlank()) {
            throw new BizException("场景信息缺失，请先生成对话场景");
        }
        // 一致性校验：场景摘要须与 step=1 留痕一致（用户编辑过场景则 hash 变化，需重新生成场景留痕口径）
        checkStepHash(req.getSessionId(), SparringAiGenRecord.STEP_SCENE, req.getSceneHash());

        long start = System.currentTimeMillis();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("sceneJson", canonical(req.getScene()));
        inputs.put("materialsText", joinMaterialsText(req.getMaterials()));

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("gen-outline", inputs);
        AiGenRespDtos.GenOutlineResp resp = new AiGenRespDtos.GenOutlineResp();
        resp.setOutlines(readOutlines(readStructured(outputs)));
        if (resp.getOutlines().isEmpty()) {
            throw new BizException("AI 未返回对话大纲，请重试");
        }

        saveRecord(req.getSessionId(), SparringAiGenRecord.STEP_OUTLINE,
                req.getSceneHash(), canonical(req), canonical(resp));
        log.info("大纲生成完成 sessionId={} outlines={} elapsedMs={}",
                req.getSessionId(), resp.getOutlines().size(), System.currentTimeMillis() - start);
        return resp;
    }

    /** SG-07 话术生成：场景 + 编辑后大纲 → 工作流③话轮数组（语言 zh/en）。 */
    @Override
    public AiGenRespDtos.GenScriptResp genScript(AiGenDtos.GenScriptReq req) {
        validateSession(req.getSessionId());
        if (req.getOutlines() == null || req.getOutlines().isEmpty()) {
            throw new BizException("对话大纲为空，请先生成大纲");
        }
        // 一致性：场景摘要仍须匹配 step=1 留痕（大纲允许用户编辑，不校验 step=2）
        checkStepHash(req.getSessionId(), SparringAiGenRecord.STEP_SCENE, req.getSceneHash());
        String language = "en".equalsIgnoreCase(req.getLanguage()) ? "en" : "zh";

        long start = System.currentTimeMillis();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("sceneJson", canonical(req.getScene()));
        inputs.put("outlinesJson", canonical(req.getOutlines()));
        inputs.put("language", language);

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("gen-script", inputs);
        AiGenRespDtos.GenScriptResp resp = new AiGenRespDtos.GenScriptResp();
        resp.setRounds(readRounds(readStructured(outputs)));
        if (resp.getRounds().size() != req.getOutlines().size()) {
            // 模型轮数漂移防线：与大纲环节数不一致直接判失败（前端可重试）
            throw new BizException("AI 生成的对话轮数(" + resp.getRounds().size()
                    + ")与大纲环节数(" + req.getOutlines().size() + ")不一致，请重试");
        }

        saveRecord(req.getSessionId(), SparringAiGenRecord.STEP_SCRIPT,
                req.getSceneHash(), canonical(req), canonical(resp));
        resp.setElapsedMs(System.currentTimeMillis() - start);
        return resp;
    }

    /** SG-08 导入编排：话轮数组 + firstSpeaker → 前端画布 nodes/edges（Java 版 resolve_round_node）。 */
    @Override
    public AiGenRespDtos.ImportScriptResp importScript(AiGenDtos.ImportScriptReq req) {
        List<AiGenDtos.RoundItem> rounds = req.getRounds();
        if (rounds == null || rounds.isEmpty()) {
            throw new BizException("请至少勾选一轮话术");
        }
        // 大节点类型：学员先说=3"学员问陪练者说"，其余（含空值）=2"陪练者问学员说"——与标准 DSL 一致
        boolean studentFirst = "student".equalsIgnoreCase(req.getFirstSpeaker());
        int roundNodeType = studentFirst ? 3 : 2;
        String roundNodeName = studentFirst ? "学员问陪练者说" : "陪练者问学员说";

        // 与前端画布 BIG_NODE_STEP 常量一致（TemplateFlowDesign.vue），保证导入后布局节奏相同
        final int bigNodeStep = 260;
        final int baseX = 300;
        final int baseY = 100;

        List<AiGenRespDtos.ImportNode> nodes = new ArrayList<>();
        List<AiGenRespDtos.ImportEdge> edges = new ArrayList<>();

        AiGenRespDtos.ImportNode startNode = new AiGenRespDtos.ImportNode();
        startNode.setNodeType(1);
        startNode.setNodeName("开始");
        startNode.setPositionX(baseX);
        startNode.setPositionY(baseY);
        nodes.add(startNode);

        for (int i = 0; i < rounds.size(); i++) {
            AiGenDtos.RoundItem round = rounds.get(i);
            AiGenRespDtos.ImportNode node = new AiGenRespDtos.ImportNode();
            node.setNodeType(roundNodeType);
            node.setNodeName(roundNodeName);
            node.setTrainerQuestion(round.getTrainerQuestion());
            node.setStandardScript(round.getStandardScript());
            node.setAnalysis(round.getAnalysis());
            List<AiGenRespDtos.ImportKeyPoint> keyPoints = new ArrayList<>();
            List<AiGenDtos.KeyPointItem> source = round.getKeyPoints() == null
                    ? List.of() : round.getKeyPoints();
            for (int j = 0; j < source.size(); j++) {
                AiGenRespDtos.ImportKeyPoint kp = new AiGenRespDtos.ImportKeyPoint();
                kp.setId("kp-" + i + "-" + j);
                kp.setContent(source.get(j).getContent());
                kp.setOrder(source.get(j).getOrder() == null ? j + 1 : source.get(j).getOrder());
                keyPoints.add(kp);
            }
            node.setKeyPoints(keyPoints);
            node.setPositionX(baseX);
            node.setPositionY(baseY + (i + 1) * bigNodeStep);
            nodes.add(node);

            AiGenRespDtos.ImportEdge edge = new AiGenRespDtos.ImportEdge();
            edge.setSource(i);
            edge.setTarget(i + 1);
            edge.setWeight(1);
            edges.add(edge);
        }

        AiGenRespDtos.ImportNode endNode = new AiGenRespDtos.ImportNode();
        endNode.setNodeType(4);
        endNode.setNodeName("结束");
        endNode.setPositionX(baseX);
        endNode.setPositionY(baseY + (rounds.size() + 1) * bigNodeStep);
        nodes.add(endNode);
        AiGenRespDtos.ImportEdge tail = new AiGenRespDtos.ImportEdge();
        tail.setSource(nodes.size() - 2);
        tail.setTarget(nodes.size() - 1);
        tail.setWeight(1);
        edges.add(tail);

        AiGenRespDtos.ImportScriptResp resp = new AiGenRespDtos.ImportScriptResp();
        resp.setNodes(nodes);
        resp.setEdges(edges);
        return resp;
    }

    /** 素材列表 → 工作流素材文本块：文件名标注 + 内容拼接；空素材返回空串（质量自担，01 SG-02）。 */
    @Override
    public String joinMaterialsText(List<AiGenDtos.MaterialItem> materials) {
        if (materials == null || materials.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AiGenDtos.MaterialItem item : materials) {
            if (item == null || item.getText() == null || item.getText().isBlank()) {
                continue;
            }
            sb.append("【素材：").append(item.getFileName() == null ? "未命名" : item.getFileName())
                    .append(Boolean.TRUE.equals(item.getTruncated()) ? "（已截断）" : "")
                    .append("】\n")
                    .append(item.getText().trim())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    // ---- 内部工具 ----

    /** Dify structured_output 出参：end 节点变量（sceneResult/outlineResult/scriptResult）。 */
    private Map<String, Object> readStructured(Map<String, Object> outputs) {
        for (String key : List.of("sceneResult", "outlineResult", "scriptResult")) {
            Object value = outputs.get(key);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            }
        }
        throw new BizException("AI 服务返回结构异常，请重试");
    }

    private AiGenRespDtos.GenSceneResp readScene(Map<String, Object> data) {
        AiGenRespDtos.GenSceneResp resp = new AiGenRespDtos.GenSceneResp();
        resp.setScene(text(data, "scene"));
        resp.setTrainerRole(text(data, "trainerRole"));
        resp.setTrainerProfile(text(data, "trainerProfile"));
        resp.setStudentRole(text(data, "studentRole"));
        resp.setGoal(text(data, "goal"));
        if (resp.getScene().isBlank()) {
            throw new BizException("AI 未返回对话场景，请重试");
        }
        return resp;
    }

    @SuppressWarnings("unchecked")
    private List<AiGenRespDtos.OutlineItem> readOutlines(Map<String, Object> data) {
        List<AiGenRespDtos.OutlineItem> result = new ArrayList<>();
        Object outlines = data.get("outlines");
        if (outlines instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    AiGenRespDtos.OutlineItem outline = new AiGenRespDtos.OutlineItem();
                    outline.setName(text((Map<String, Object>) map, "name"));
                    outline.setAssessmentPoints(text((Map<String, Object>) map, "assessmentPoints"));
                    outline.setStrategy(text((Map<String, Object>) map, "strategy"));
                    result.add(outline);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<AiGenRespDtos.RoundItem> readRounds(Map<String, Object> data) {
        List<AiGenRespDtos.RoundItem> result = new ArrayList<>();
        Object nodes = data.get("nodes");
        if (nodes instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> map = (Map<String, Object>) item;
                AiGenRespDtos.RoundItem round = new AiGenRespDtos.RoundItem();
                round.setRound(number(map.get("round")));
                round.setTrainerQuestion(text(map, "trainerQuestion"));
                round.setStandardScript(text(map, "standardScript"));
                round.setAnalysis(text(map, "analysis"));
                List<AiGenRespDtos.KeyPointItem> keyPoints = new ArrayList<>();
                if (map.get("keyPoints") instanceof List<?> kpList) {
                    for (Object kp : kpList) {
                        if (kp instanceof Map<?, ?> kpMap) {
                            AiGenRespDtos.KeyPointItem point = new AiGenRespDtos.KeyPointItem();
                            point.setContent(String.valueOf(kpMap.get("content") == null ? "" : kpMap.get("content")));
                            point.setOrder(number(kpMap.get("order")));
                            keyPoints.add(point);
                        }
                    }
                }
                round.setKeyPoints(keyPoints);
                result.add(round);
            }
        }
        return result;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer number(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private String normalizeSpeaker(String firstSpeaker) {
        return "student".equalsIgnoreCase(firstSpeaker) ? "student" : "trainer";
    }

    private void validateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException("sessionId 不能为空");
        }
    }

    /** 步骤一致性校验：入参摘要与留痕比对，不符提示重新生成（02 §7 难点对策）。 */
    private void checkStepHash(String sessionId, int step, String sceneHash) {
        SparringAiGenRecord record = latestRecord(sessionId, step);
        if (record == null) {
            throw new BizException("场景信息已变更，请重新生成");
        }
        if (sceneHash == null || !sceneHash.equals(record.getRequestHash())) {
            throw new BizException("场景信息已变更，请重新生成大纲");
        }
    }

    /** 留痕写入：同 session+step 删旧插新（"返回重新生成"覆盖语义）。
     *  request/result 调用方已 canonical 为 JSON 字符串——此处直接落库（双重序列化是
     *  E2E 联调修正点：此前 String 再 canonical 一次会得到带引号的转义字符串）。 */
    private void saveRecord(String sessionId, int step, String requestHash,
                            String requestJson, String resultJson) {
        recordMapper.delete(Wrappers.<SparringAiGenRecord>lambdaQuery()
                .eq(SparringAiGenRecord::getSessionId, sessionId)
                .eq(SparringAiGenRecord::getStep, step));
        SparringAiGenRecord record = new SparringAiGenRecord();
        record.setSessionId(sessionId);
        record.setStep(step);
        record.setRequestHash(requestHash == null ? "" : requestHash);
        record.setRequestJson(requestJson);
        record.setResultJson(resultJson);
        record.setCreatedBy("0");
        recordMapper.insert(record);
    }

    private SparringAiGenRecord latestRecord(String sessionId, int step) {
        return recordMapper.selectOne(Wrappers.<SparringAiGenRecord>lambdaQuery()
                .eq(SparringAiGenRecord::getSessionId, sessionId)
                .eq(SparringAiGenRecord::getStep, step)
                .orderByDesc(SparringAiGenRecord::getCreatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 场景五字段 → 前端同构紧凑 JSON（JSON.stringify 字节一致）。
     * E2E 联调修正（10-08）：Jackson 对 Map 的默认输出在冒号后带空格，与 JSON.stringify 不同；
     * hash 口径必须两端字节一致，故此处按前端序列化规则手写拼接（键序=SceneData 声明序，
     * 值经 JSON 字符串转义——正斜杠不转义、双引号/反斜杠/控制字符转义，与 JS 一致）。
     */
    private String sceneOnly(AiGenRespDtos.GenSceneResp resp) {
        return "{\"scene\":" + jsonString(resp.getScene())
                + ",\"trainerRole\":" + jsonString(resp.getTrainerRole())
                + ",\"trainerProfile\":" + jsonString(resp.getTrainerProfile())
                + ",\"studentRole\":" + jsonString(resp.getStudentRole())
                + ",\"goal\":" + jsonString(resp.getGoal())
                + "}";
    }

    /** JSON 字符串字面量（与 JS JSON.stringify 的转义规则对齐：引号/反斜杠/控制字符）。 */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append('\\').append('"');
                case '\\' -> sb.append('\\').append('\\');
                case '\n' -> sb.append('\\').append('n');
                case '\r' -> sb.append('\\').append('r');
                case '\t' -> sb.append('\\').append('t');
                case '\b' -> sb.append('\\').append('b');
                case '\f' -> sb.append('\\').append('f');
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 对象 → 规范 JSON。注意：Jackson 默认输出在冒号后带空格（": "），与前端 JSON.stringify
     *  的紧凑格式不同——sceneHash 对齐场景由 sceneOnly+手写序列化保证（见下）。 */
    private String canonical(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BizException("请求序列化失败");
        }
    }

    /** SHA-256 摘要（hex）。 */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception exception) {
            throw new BizException("摘要计算失败");
        }
    }
}
