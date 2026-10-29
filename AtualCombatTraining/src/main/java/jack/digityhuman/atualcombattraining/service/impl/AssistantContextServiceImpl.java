package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文拼装实现（FP-35 编排智能体的输入变量）
 * <p>
 * theme_status 取值与 DSL 提示词约定的状态机一致：new（新会话无主题）/ draft / published。
 * 主题状态 1（发布中=初始化中）按 draft 处理——内容仍可在助手里继续调整。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantContextServiceImpl implements AssistantContextService {

    /** 材料摘要拼进上下文的长度上限（与 DSL start 节点 max_length 对齐） */
    private static final int MATERIAL_SUMMARY_MAX = 20_000;
    /** 单条材料摘要取用长度 */
    private static final int SINGLE_MATERIAL_MAX = 2_000;

    /** 主题状态常量 */
    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHING = 1;
    private static final int STATUS_PUBLISHED = 3;
    private static final int STATUS_PAUSED = 4;
    private static final int STATUS_FINISHED = 5;

    private final ActThemeMapper themeMapper;
    private final ActAiMaterialMapper materialMapper;
    private final ArtifactService artifactService;

    @Override
    public Map<String, String> buildInputs(ActAiSession session) {
        Map<String, String> inputs = new LinkedHashMap<>();
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            // 建主题前：编排智能体第 1 阶段需能读到用户已上传材料的摘要，故按会话归集
            inputs.put("theme_status", "new");
            inputs.put("artifact_summary", "");
            inputs.put("material_summary", buildMaterialSummary(null, session.getId()));
            return inputs;
        }
        ActTheme theme = themeMapper.selectById(themeId);
        inputs.put("theme_status", resolveThemeStatus(theme));
        inputs.put("artifact_summary", safe(artifactService.buildSummary(
                artifactService.findDisplay(themeId))));
        inputs.put("material_summary", buildMaterialSummary(themeId, session.getId()));
        return inputs;
    }

    @Override
    public String materialSummary(ActAiSession session) {
        if (session == null) {
            return "";
        }
        return buildMaterialSummary(session.getThemeId(), session.getId());
    }

    /** 主题状态 → DSL 状态机取值 */
    private String resolveThemeStatus(ActTheme theme) {
        if (theme == null || theme.getStatus() == null) {
            return "new";
        }
        int status = theme.getStatus();
        if (status == STATUS_DRAFT || status == STATUS_PUBLISHING) {
            return "draft";
        }
        if (status == STATUS_PUBLISHED || status == STATUS_PAUSED || status == STATUS_FINISHED) {
            return "published";
        }
        return "draft";
    }

    /**
     * 材料摘要：文件名 + 解析摘要（超长整体截断）。
     * 有主题时按 theme_id 取该主题材料；建主题前（themeId 为空）按 session_id 取会话材料，
     * 保证编排智能体在"先传材料后定主题"的第 1 阶段也能读到材料内容。
     */
    private String buildMaterialSummary(String themeId, String sessionId) {
        LambdaQueryWrapper<ActAiMaterial> wrapper = new LambdaQueryWrapper<>();
        if (themeId != null && !themeId.isBlank()) {
            wrapper.eq(ActAiMaterial::getThemeId, themeId);
        } else {
            wrapper.eq(ActAiMaterial::getSessionId, sessionId);
        }
        wrapper.orderByAsc(ActAiMaterial::getCreatedAt);
        List<ActAiMaterial> materials = materialMapper.selectList(wrapper);
        if (materials.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ActAiMaterial material : materials) {
            text.append("【材料】").append(material.getName()).append('\n');
            String summary = material.getSummary();
            if (summary != null && !summary.isBlank()) {
                text.append(summary.length() <= SINGLE_MATERIAL_MAX
                        ? summary : summary.substring(0, SINGLE_MATERIAL_MAX));
                text.append('\n');
            }
            if (text.length() >= MATERIAL_SUMMARY_MAX) {
                break;
            }
        }
        return text.length() <= MATERIAL_SUMMARY_MAX
                ? text.toString() : text.substring(0, MATERIAL_SUMMARY_MAX);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}