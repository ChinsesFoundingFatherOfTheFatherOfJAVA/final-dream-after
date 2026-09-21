package jack.digityhuman.atualcombattraining.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;

/**
 * artifact 服务（FP-38 版本化存储）
 * <p>
 * 版本语义：一个主题一条 artifact 主记录（id 稳定），status=working 的行为"工作区"——
 * 对话期间原地更新且 version_no 不变；确认后该行转 confirmed，下次修改再基于 confirmed
 * 内容新开一个 version_no+1 的工作区行。
 */
public interface ArtifactService {

    /** 工作区行（对话中持续修改的行），无则 null */
    ActAiArtifact findWorking(String themeId);

    /** 最新的已确认行，无则 null */
    ActAiArtifact findLatestConfirmed(String themeId);

    /**
     * 展示用 artifact：工作区优先（未确认的修改不丢失），无工作区则取最新 confirmed；
     * 都没有返回 null（新建主题尚未建主题/产物）。
     */
    ActAiArtifact findDisplay(String themeId);

    /** 按 (id, version_no) 精确取行 */
    ActAiArtifact get(String artifactId, Integer versionNo);

    /** 取工作区行，不存在则创建（有 confirmed 则基于其内容与版本号 +1，否则空骨架 v1） */
    ActAiArtifact ensureWorking(String themeId);

    /**
     * 应用 JSON-Patch（op=full_replace）到工作区 artifact。
     *
     * @return 应用后的版本号
     */
    int applyFullReplacePatches(String themeId, ArrayNode patches);

    /**
     * 用工作流生成结果整体替换工作区 artifact（materials 原样保留——工作流不管理材料）。
     *
     * @return 应用后的版本号
     */
    int replaceGenerated(String themeId, ObjectNode generated);

    /**
     * 用工作流的<b>中间产物</b>增量合并进工作区 artifact（"渐次出现"，FP-33）：
     * 只覆盖 partial 里出现且非 null 的顶级字段，其余字段保留工作区现值；materials 始终保留。
     * 与 {@link #replaceGenerated} 的区别：后者是最终权威结果（8 字段整体替换），
     * 本方法用于三段式生成过程中逐段落地（场景 → 画像+冰山 → 最终聚合）。
     *
     * @param partial 中间产物（可只含部分顶级字段）
     * @return 合并后的版本号
     */
    int mergeGeneratedPartial(String themeId, ObjectNode partial);

    /**
     * 把单场景生成的案例合入工作区 artifact（cases[] 追加 + caseArtifactIds 追加）。
     *
     * @param scenarioId artifact 内的场景 ID（sc-01 式）
     * @return 合入结果（含新生成的 caseArtifactId 与难度）
     */
    CaseMergeResult mergeCase(String themeId, String scenarioId, ObjectNode caseJson);

    /**
     * 更新培训材料的「学员可见」开关（右面板交互，FP-42）：
     * 在 artifact.materials[] 中按 materialId 定位（兼容 id / fileServId / fileId 三种字段）并改写 visibility。
     *
     * @param themeId 主题 ID
     * @param materialId 材料 ID（上传接口返回的 fileId 即 materials[].id）
     * @param visibility 1 学员可见 / 0 内部可见
     * @return 更新结果（含用于事件回传的 full_replace 补丁）
     */
    MaterialVisibilityResult updateMaterialVisibility(String themeId, String materialId, Integer visibility);

    /**
     * 追加或更新一条材料到工作区 artifact.materials（FP-42 解析链路的服务端写入）：
     * 以 material.id（=上传 fileId）为键，已存在则整体替换该条，不存在则追加到数组末尾。
     *
     * @param themeId 主题 ID
     * @param material 材料条目（字段见 03 §3.5 materials）
     * @return 更新后的版本号
     */
    int appendOrUpdateMaterial(String themeId, ObjectNode material);

    /** artifact 结构摘要（场景名/画像类型/维度名与权重/案例计数），喂编排智能体避免全量 JSON 进 prompt */
    String buildSummary(ActAiArtifact artifact);

    /** 案例合入结果 */
    record CaseMergeResult(String caseArtifactId, String scenarioId, String scriptName, Integer difficulty) {
    }

    /**
     * 材料可见性更新结果。
     *
     * @param artifactId 产物 ID
     * @param versionNo 更新后的版本号（工作区原地更新，版本号不变）
     * @param materialId 命中的材料 ID
     * @param visibility 更新后的可见性
     * @param patchContent 供 biz-side-manage-artifact-updated 事件回传的补丁数组
     */
    record MaterialVisibilityResult(String artifactId, Integer versionNo, String materialId,
                                    Integer visibility, ArrayNode patchContent) {
    }
}