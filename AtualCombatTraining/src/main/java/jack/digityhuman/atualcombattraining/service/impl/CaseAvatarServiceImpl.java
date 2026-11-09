package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.CaseAvatarSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.CaseAvatarResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import jack.digityhuman.atualcombattraining.service.CaseAvatarService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 案例形象设置实现（L0 上传覆盖写入侧，2026-11-05）。
 * <p>
 * 核心不变式：<b>artifact 是权威源，列是当前版本的即时投影</b>。
 * 两处同事务双写——只写列会在下次「确认」时被「先删后插」冲掉（本类存在的理由）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseAvatarServiceImpl implements CaseAvatarService {

    /** actor_avatar_url 列宽（与契约 05 §9.3 的 VARCHAR(512) 对齐，超长先截断） */
    private static final int LEN_AVATAR_URL = 512;

    private final ActThemeMapper themeMapper;
    private final ArtifactService artifactService;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActScenarioUnitMapper unitMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CaseAvatarResp saveAvatar(String themeId, CaseAvatarSaveReq req) {
        if (themeId == null || themeId.isBlank()) {
            throw new BizException("主题不存在");
        }
        if (req == null) {
            throw new BizException("参数不能为空");
        }
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在：" + themeId);
        }

        // 1. 解析定位键：scriptId 只是便利入口，最终都必须落到 scriptName + 场景名
        Locator locator = resolveLocator(themeId, req);
        String targetUrl = cleanUrl(req.getAvatarUrl());

        // 2. 写 artifact（权威源）。确认落库按它重建 act_case_script 行，形象因此不会丢
        ActAiArtifact artifact = artifactService.findWorking(themeId);
        if (artifact == null) {
            artifact = artifactService.findLatestConfirmed(themeId);
        }
        if (artifact == null) {
            throw new BizException("该主题还没有生成内容，无法设置形象");
        }
        ObjectNode root = JsonSupport.obj(artifact.getContent());
        if (root == null) {
            throw new BizException("产物内容非法，无法设置形象");
        }
        Located located = locateInArtifact(root, locator);
        int artifactVersionNo = artifactService.applyFullReplacePatches(
                themeId, buildPatches(located, targetUrl));

        // 3. 写列（当前版本即时可见；发布时 compileOne 也读它对 L0 判断）
        int columnUpdated = updateCaseScriptColumns(themeId, located, targetUrl);

        log.info("设置案例形象 themeId={} scenario={} case={} url={} artifactVersion={} 列更新={}",
                themeId, located.scenarioName(), located.scriptName(), targetUrl,
                artifactVersionNo, columnUpdated);

        CaseAvatarResp resp = new CaseAvatarResp();
        resp.setThemeId(themeId);
        resp.setScenarioName(located.scenarioName());
        resp.setScriptName(located.scriptName());
        resp.setAvatarUrl(targetUrl);
        resp.setArtifactVersionNo(artifactVersionNo);
        resp.setColumnUpdated(columnUpdated);
        return resp;
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 归一化入参为定位键：scriptId 先查行取 scriptName/场景名。
     * <p>
     * 为什么 scriptId 不能直接定终：{@code act_case_script} 行 ID 在每次确认落库时都会重建
     * （先删后插），它只能当"这一次"的便利入口，<b>持久定位键必须是 scriptName</b>。
     */
    private Locator resolveLocator(String themeId, CaseAvatarSaveReq req) {
        String scenarioName = blankToNull(req.getScenarioName());
        String scriptName = blankToNull(req.getScriptName());
        String scriptId = blankToNull(req.getScriptId());

        if (scriptName == null && scriptId == null) {
            throw new BizException("请提供 scriptName 或 scriptId 以定位案例");
        }
        if (scriptName != null) {
            return new Locator(scenarioName, scriptName);
        }
        ActCaseScript row = caseScriptMapper.selectById(scriptId);
        if (row == null) {
            throw new BizException("案例不存在：" + scriptId);
        }
        // 只允许改本主题的案例：跨主题改图属于越权写
        if (!themeId.equals(row.getThemeId())) {
            throw new BizException("该案例不属于当前主题");
        }
        String resolvedScenario = scenarioName;
        if (resolvedScenario == null && row.getScenarioUnitId() != null) {
            ActScenarioUnit unit = unitMapper.selectById(row.getScenarioUnitId());
            resolvedScenario = unit == null ? null : unit.getUnitName();
        }
        return new Locator(resolvedScenario, row.getScriptName());
    }

    /**
     * 在 artifact 里按场景名 + 案例标题定位，同时记下数组下标（补丁路径要用）。
     * <p>
     * 匹配到多条且未给 scenarioName 时<b>报歧义</b>，不猜——猜错等于给错误的角色换脸。
     */
    private Located locateInArtifact(ObjectNode root, Locator locator) {
        List<Located> hits = new ArrayList<>();
        int scenarioIndex = 0;
        for (JsonNode scenarioItem : JsonSupport.children(root, "scenarios")) {
            if (scenarioItem == null || !scenarioItem.isObject()) {
                scenarioIndex++;
                continue;
            }
            ObjectNode scenario = (ObjectNode) scenarioItem;
            String scenarioName = JsonSupport.text(scenario, "name");
            if (locator.scenarioName() == null || locator.scenarioName().equals(scenarioName)) {
                int caseIndex = 0;
                for (JsonNode caseItem : JsonSupport.children(scenario, "cases")) {
                    if (caseItem != null && caseItem.isObject()
                            && locator.scriptName().equals(
                                    JsonSupport.text((ObjectNode) caseItem, "scriptName"))) {
                        hits.add(new Located(scenarioName, locator.scriptName(), scenarioIndex, caseIndex));
                    }
                    caseIndex++;
                }
            }
            scenarioIndex++;
        }
        if (hits.isEmpty()) {
            throw new BizException("产物里找不到该案例：" + locator.scriptName()
                    + (locator.scenarioName() == null ? "" : "（场景：" + locator.scenarioName() + "）"));
        }
        if (hits.size() > 1) {
            throw new BizException("产物里有 " + hits.size() + " 个同名案例：" + locator.scriptName()
                    + "，请传 scenarioName 指定场景");
        }
        return hits.get(0);
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 构造 full_replace 补丁，精确指向 {@code /scenarios/{si}/cases/{ci}/actorAvatarUrl}。
     * <p>
     * 为什么不用"整体替换 scenarios"：那会把整份场景数组（含案例全文）塞进一个补丁，
     * 推给前端的事件体积巨大，也更容易因别的字段变动产生误覆盖。单键补丁最小、可审计。
     * <p>
     * 清除覆盖时 value 传 JSON null：与"键在但为 null"等价，下游只认"有值即覆盖"。
     * 写入走既有 {@link ArtifactService#applyFullReplacePatches}，保证 content_hash、
     * 版本号与其它 artifact 写入口径完全一致。
     */
    private ArrayNode buildPatches(Located located, String avatarUrl) {
        ObjectNode patch = JsonSupport.blankObject();
        patch.put("op", "full_replace");
        patch.put("path", "/scenarios/" + located.scenarioIndex() + "/cases/"
                + located.caseIndex() + "/actorAvatarUrl");
        patch.set("value", avatarUrl == null ? JsonSupport.toNode(null) : JsonSupport.toNode(avatarUrl));
        patch.put("reason", "admin-set-case-avatar");
        ArrayNode patches = JsonSupport.blankArray();
        patches.add(patch);
        return patches;
    }

    /**
     * 同步 {@code act_case_script.actor_avatar_url} 列。
     * <p>
     * 只按 scriptName + 场景名匹配<b>该主题</b>的行（历史版本是快照，不该被后台改动）。
     * 返回更新行数——<b>0 不报错</b>：表示案例尚未确认落库（artifact 有、表里还没有行），
     * 下次确认落库会按 artifact 重建出带形象的行（这正是双写的意义）。
     */
    private int updateCaseScriptColumns(String themeId, Located located, String avatarUrl) {
        List<ActCaseScript> rows = caseScriptMapper.selectList(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getScriptName, located.scriptName()));
        int updated = 0;
        for (ActCaseScript row : rows) {
            if (!belongsToScenario(row, located.scenarioName())) {
                continue;
            }
            ActCaseScript update = new ActCaseScript();
            update.setId(row.getId());
            update.setActorAvatarUrl(ArtifactSupport.truncate(avatarUrl, LEN_AVATAR_URL));
            update.setUpdatedAt(LocalDateTime.now());
            caseScriptMapper.updateById(update);
            updated++;
        }
        return updated;
    }

    /** 行上只有 scenarioUnitId，用单元名与 artifact 场景名对齐（单元缺失时视为不匹配） */
    private boolean belongsToScenario(ActCaseScript row, String scenarioName) {
        if (scenarioName == null || row.getScenarioUnitId() == null) {
            return true;
        }
        ActScenarioUnit unit = unitMapper.selectById(row.getScenarioUnitId());
        return unit == null || scenarioName.equals(unit.getUnitName());
    }

    /** 空串视同 null（清除覆盖），并截断到列宽 */
    private static String cleanUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ArtifactSupport.truncate(raw.trim(), LEN_AVATAR_URL);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 定位键（内部统一形态）：场景名可选 + 案例标题必填 */
    record Locator(String scenarioName, String scriptName) {
    }

    /** artifact 里实际命中的位置（含数组下标，补丁路径要用） */
    record Located(String scenarioName, String scriptName, int scenarioIndex, int caseIndex) {
    }
}
