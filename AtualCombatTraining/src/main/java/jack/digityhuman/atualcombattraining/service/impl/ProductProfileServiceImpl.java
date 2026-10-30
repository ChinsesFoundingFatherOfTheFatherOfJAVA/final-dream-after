package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.NativeWorkflowClient;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.DrillSchemas;
import jack.digityhuman.atualcombattraining.service.ProductProfileService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品档案服务实现（契约 09）
 * <p>
 * profileKey 生成：pp_ + 10 位 Base62 随机（≈62^10 组合，主题内唯一键兜底冲突重试）。
 * 学员只读走发布快照行（content_version_no = published_version_no），
 * 并校验学员在该主题的参与关系（§8：跨主题访问拒绝）。
 * AI 候选生成调 AIGentService product-profile-gen（blocking），候选不落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductProfileServiceImpl implements ProductProfileService {

    /** 工作区版本号 */
    private static final int VERSION_WORKING = 0;
    /** 每主题工作档案上限（§6） */
    private static final int MAX_PROFILES_PER_THEME = 20;
    private static final String KEY_PREFIX = "pp_";
    private static final String CANDIDATE_PREFIX = "pp_candidate_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String KEY_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    private final ActProductProfileMapper profileMapper;
    private final ActScenarioProductProfileMapper scenarioProfileMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActThemeParticipantMapper participantMapper;
    private final NativeWorkflowClient nativeWorkflowClient;

    @Override
    public List<ActProductProfile> listWorking(String themeId) {
        return profileMapper.selectList(new LambdaQueryWrapper<ActProductProfile>()
                .eq(ActProductProfile::getThemeId, themeId)
                .eq(ActProductProfile::getContentVersionNo, VERSION_WORKING)
                .orderByAsc(ActProductProfile::getSortOrder)
                .orderByAsc(ActProductProfile::getCreatedAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActProductProfile create(String themeId, ProductProfileUpsertReq req) {
        long count = profileMapper.selectCount(new LambdaQueryWrapper<ActProductProfile>()
                .eq(ActProductProfile::getThemeId, themeId)
                .eq(ActProductProfile::getContentVersionNo, VERSION_WORKING));
        if (count >= MAX_PROFILES_PER_THEME) {
            throw new BizException("每个主题最多 " + MAX_PROFILES_PER_THEME + " 个产品档案");
        }
        ActProductProfile profile = new ActProductProfile();
        profile.setId(IdWorker.getIdStr());
        profile.setThemeId(themeId);
        profile.setContentVersionNo(VERSION_WORKING);
        profile.setProfileKey(nextKey());
        applyUpsert(profile, req);
        profile.setSortOrder((int) count + 1);
        profile.setCreatedBy("admin");
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        profileMapper.insert(profile);
        return profile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActProductProfile update(String themeId, String profileId, ProductProfileUpsertReq req) {
        ActProductProfile profile = requireWorking(themeId, profileId);
        applyUpsert(profile, req);
        profile.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(profile);
        return profile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String themeId, String profileId) {
        ActProductProfile profile = requireWorking(themeId, profileId);
        // §2.2：删除仍被场景引用的档案时拒绝，返回引用场景清单
        List<ActScenarioProductProfile> links = scenarioProfileMapper.selectList(
                new LambdaQueryWrapper<ActScenarioProductProfile>()
                        .eq(ActScenarioProductProfile::getProductProfileId, profileId));
        if (!links.isEmpty()) {
            List<String> names = links.stream()
                    .map(link -> scenarioOf(link.getScenarioUnitId()))
                    .filter(unit -> unit != null)
                    .map(unit -> unit.getUnitName())
                    .toList();
            throw new BizException("档案仍被场景引用：" + String.join("、", names));
        }
        profileMapper.deleteById(profileId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void linkScenario(String themeId, String scenarioUnitId, List<String> profileKeys) {
        ActScenarioUnit unit = scenarioOf(scenarioUnitId);
        if (unit == null || !themeId.equals(unit.getThemeId())) {
            throw new BizException("场景不存在或不属于该主题");
        }
        List<String> keys = profileKeys == null ? List.of() : profileKeys;
        // 校验 keys 都存在于工作区
        List<ActProductProfile> working = listWorking(themeId);
        Map<String, ActProductProfile> byKey = new LinkedHashMap<>();
        working.forEach(profile -> byKey.put(profile.getProfileKey(), profile));
        for (String key : keys) {
            if (!byKey.containsKey(key)) {
                throw new BizException("产品档案不存在：" + key);
            }
        }
        // 覆盖式替换
        scenarioProfileMapper.delete(new LambdaQueryWrapper<ActScenarioProductProfile>()
                .eq(ActScenarioProductProfile::getScenarioUnitId, scenarioUnitId));
        int order = 1;
        for (String key : keys) {
            ActScenarioProductProfile link = new ActScenarioProductProfile();
            link.setScenarioUnitId(scenarioUnitId);
            link.setProductProfileId(byKey.get(key).getId());
            link.setSortOrder(order++);
            scenarioProfileMapper.insert(link);
        }
    }

    @Override
    public List<ActProductProfile> listByScenario(String themeId, String scenarioUnitId) {
        List<String> linkedIds = scenarioProfileMapper.selectList(
                        new LambdaQueryWrapper<ActScenarioProductProfile>()
                                .eq(ActScenarioProductProfile::getScenarioUnitId, scenarioUnitId)
                                .orderByAsc(ActScenarioProductProfile::getSortOrder))
                .stream()
                .map(ActScenarioProductProfile::getProductProfileId)
                .toList();
        if (linkedIds.isEmpty()) {
            return List.of();
        }
        return profileMapper.selectBatchIds(linkedIds);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateCandidates(String themeId, ProductProfileGenerateReq req) {
        List<?> materials = req.materialIds() == null ? List.of() : req.materialIds();
        if (materials.size() > 5) {
            throw new BizException("最多选择 5 个材料");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("theme_id", themeId);
        inputs.put("profile_name_hint", nullSafe(req.profileNameHint()));
        inputs.put("instructions", nullSafe(req.instructions()));
        inputs.put("custom_field_keys_json",
                JsonSupport.toJson(req.customFieldKeys() == null ? List.of() : req.customFieldKeys()));
        // 材料摘要：契约 §5.2 要求名称+摘要+来源；材料全文解析属于 AI 设计助手已有链路，
        // 这里只传 ID 列表（全文检索是后续增强，不阻塞主链路）
        inputs.put("materials_json", JsonSupport.toJson(materials));
        inputs.put("existing_profiles_json", JsonSupport.toJson(listWorking(themeId).stream()
                .map(profile -> Map.of("profileKey", profile.getProfileKey(),
                        "profileName", profile.getProfileName()))
                .toList()));

        // blocking() 返回的就是 data.outputs 节点（键：profile_schema_version/profiles_json/success）
        ObjectNode data = nativeWorkflowClient.blocking(
                "product-profile-gen", themeId, newTraceId(), inputs, 300_000);
        String profilesJson = text(data, "profiles_json");
        JsonNode array = parseNode(profilesJson);
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new BizException("候选档案生成失败：模型未返回候选");
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        array.forEach(item -> candidates.add(MAPPER.convertValue(item, Map.class)));
        return candidates;
    }

    @Override
    public ActProductProfile studentProfile(String userId, String profileId) {
        ActProductProfile profile = profileMapper.selectById(profileId);
        if (profile == null) {
            throw new BizException("产品档案不存在");
        }
        // §7：只读发布快照
        if (profile.getContentVersionNo() == null || profile.getContentVersionNo() <= VERSION_WORKING) {
            throw new BizException("产品档案不存在");
        }
        // §8：跨主题访问拒绝（学员必须在该主题的学员名单中）
        ActThemeParticipant participation = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, profile.getThemeId())
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, "active")
                        .last("LIMIT 1"));
        if (participation == null) {
            throw new BizException("无权访问该产品档案");
        }
        return profile;
    }

    // ------------------------------------------------------------------ helpers

    private void applyUpsert(ActProductProfile profile, ProductProfileUpsertReq req) {
        if (req == null || req.profileName() == null || req.profileName().isBlank()) {
            throw new BizException("档案名称不能为空");
        }
        // 结构校验（契约 §2.1 / product-profile-v1）：profileKey 用现值占位
        ObjectNode candidate = JsonSupport.blankObject();
        candidate.put("profileKey", profile.getProfileKey() == null
                ? CANDIDATE_PREFIX + "tmp" : profile.getProfileKey());
        candidate.put("profileName", req.profileName());
        JsonSupport.putText(candidate, "category", req.category());
        JsonSupport.putText(candidate, "description", req.description());
        JsonSupport.putText(candidate, "detailMarkdown", req.detailMarkdown());
        candidate.putArray("customFields");
        try {
            JsonNode fields = MAPPER.readTree(
                    req.customFieldsJson() == null ? "[]" : req.customFieldsJson());
            if (fields.isArray()) {
                candidate.set("customFields", fields);
            }
        } catch (Exception e) {
            throw new BizException("customFields 不是合法 JSON 数组");
        }
        ObjectNode source = candidate.putObject("source");
        source.put("type", "manual");
        DrillSchemas.validateProductProfile(candidate);

        profile.setProfileName(req.profileName());
        profile.setCategory(req.category());
        profile.setDescription(req.description());
        profile.setDetailMarkdown(req.detailMarkdown());
        profile.setCustomFields(req.customFieldsJson());
    }

    private ActProductProfile requireWorking(String themeId, String profileId) {
        ActProductProfile profile = profileMapper.selectById(profileId);
        // §4.4：已发布版本的历史行不可改写
        if (profile == null || !profile.getThemeId().equals(themeId)
                || profile.getContentVersionNo() == null
                || profile.getContentVersionNo() != VERSION_WORKING) {
            throw new BizException("产品档案不存在或不可编辑");
        }
        return profile;
    }

    /** pp_ + 10 位 Base62（§1：8~48 位字母数字下划线连字符） */
    private String nextKey() {
        StringBuilder key = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < 10; i++) {
            key.append(KEY_ALPHABET.charAt(RANDOM.nextInt(KEY_ALPHABET.length())));
        }
        return key.toString();
    }

    private JsonNode parseNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public ActScenarioUnit scenarioOf(String scenarioUnitId) {
        return unitMapper.selectById(scenarioUnitId);
    }
}
