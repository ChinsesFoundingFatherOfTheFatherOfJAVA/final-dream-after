package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActCustomerProfile;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCustomerProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ContentVersionService;
import jack.digityhuman.atualcombattraining.service.ScenarioUnitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容版本服务实现
 * <p>
 * 三个不变式（违反即数据错乱）：
 * <ol>
 *   <li>v0 永不出现在 published_version_no 上；</li>
 *   <li>版本行的五表单字段是"发布时设置快照"，复制后不再随设置修改变化；</li>
 *   <li>指针切换（published/working）必须与内容行写入同事务完成，避免读空版本。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentVersionServiceImpl implements ContentVersionService {

    private final ActThemeMapper themeMapper;
    private final ActThemeVersionMapper versionMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActCustomerProfileMapper profileMapper;
    private final ActProductProfileMapper productProfileMapper;
    private final ActScenarioProductProfileMapper scenarioProductProfileMapper;
    private final ActAiMaterialMapper materialMapper;
    private final ActContentVersionMaterialMapper materialSnapshotMapper;
    private final ScenarioUnitService scenarioUnitService;

    /** act_ai_material.visibility：1=学员可见（契约 11 §6） */
    private static final int VISIBILITY_STUDENT = 1;
    /** 工作区内容版本号（act_product_profile.content_version_no=0 是工作区，&gt;0 是发布快照） */
    private static final int VERSION_WORKING = 0;
    /** 在线课堂材料 file_id 前缀（与 AssistantMaterialServiceImpl 同口径） */
    private static final String KNG_FILE_ID_PREFIX = "kng_";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int firstPublish(String themeId, String userId) {
        ActTheme theme = requireTheme(themeId);
        if (theme.getPublishedVersionNo() != null) {
            // 已发布主题不允许再走首次发布（幂等保护）
            return theme.getPublishedVersionNo();
        }
        Integer working = theme.getWorkingVersionNo() == null ? 0 : theme.getWorkingVersionNo();
        ActThemeVersion workingRow = requireVersion(themeId, working);
        LocalDateTime now = LocalDateTime.now();

        // 1. 准备 v1 版本行：迁移遗留的 legacy_content v1 行原地复活，否则新插
        ActThemeVersion legacy = versionMapper.selectOne(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, themeId)
                .eq(ActThemeVersion::getVersionNo, 1)
                .last("LIMIT 1"));
        if (legacy != null) {
            // 五表单字段以 v0 工作内容为准（v0 是迁移时从旧当前版本复制出来的）
            copyFormFields(workingRow, legacy);
            legacy.setSourceType("initial_publish");
            legacy.setPublishedAt(now);
            legacy.setPublishedBy(userId);
            legacy.setUpdatedAt(now);
            versionMapper.updateById(legacy);
        } else {
            ActThemeVersion v1 = copyVersionRow(workingRow, 1);
            v1.setSourceType("initial_publish");
            v1.setPublishedAt(now);
            v1.setPublishedBy(userId);
            versionMapper.insert(v1);
        }

        // 2. 内容行 v0 → v1（场景+维度由既有服务复制，案例/画像/材料快照按版本先删后插）
        scenarioUnitService.copyUnitsToVersion(themeId, working, 1);
        copyCases(themeId, working, 1);
        copyProfiles(themeId, working, 1);
        // 产品档案：契约 09 §4.2 要求「把 content_version_no=0 的档案复制到 v1」并「复制场景关联」。
        // 必须在 copyUnitsToVersion 之后调用：关联表靠 scenario_unit_id 归属版本，要拿新旧单元行做映射。
        copyProductProfiles(themeId, working, 1);
        // 材料是主题级数据（不按版本存），发布时把学员可见材料固化成 v1 快照（契约 11 §8）
        copyMaterials(themeId, 1);

        // 3. 指针切换
        themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .set(ActTheme::getWorkingVersionNo, 1)
                .set(ActTheme::getPublishedVersionNo, 1)
                .set(ActTheme::getFirstPublishedAt, now)
                .set(ActTheme::getUpdatedAt, now));
        log.info("首次发布完成 themeId={} 公开版本=v1 操作人={}", themeId, userId);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int startNewVersionFromPublished(String themeId, String userId) {
        ActTheme theme = requireTheme(themeId);
        Integer published = theme.getPublishedVersionNo();
        if (published == null) {
            throw new IllegalStateException("主题未发布，不能走确认升版路径：" + themeId);
        }
        ActThemeVersion publishedRow = requireVersion(themeId, published);
        int nextNo = maxVersionNo(themeId) + 1;
        ActThemeVersion next = copyVersionRow(publishedRow, nextNo);
        next.setSourceType("ai_confirm");
        next.setPublishedAt(LocalDateTime.now());
        next.setPublishedBy(userId);
        versionMapper.insert(next);
        log.info("AI 确认新建内容版本 themeId={} version={}（源版本={}）", themeId, nextNo, published);
        return nextNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markPublished(String themeId, int versionNo, String userId) {
        ActThemeVersion version = requireVersion(themeId, versionNo);
        LocalDateTime now = LocalDateTime.now();
        // 学员端材料接口只读当前发布版本的快照，指针切换前必须先把材料固化进该版本（契约 11 §8）
        copyMaterials(themeId, versionNo);
        // 产品档案同步固化（契约 09 §4.3）：目标版本行从"该版本自己的场景单元"与工作区 v0 对齐，
        // 见 copyProductProfiles 注释里对两种来源的取舍说明。
        copyProductProfiles(themeId, VERSION_WORKING, versionNo);
        versionMapper.update(null, new LambdaUpdateWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getId, version.getId())
                .set(ActThemeVersion::getPublishedAt, now)
                .set(ActThemeVersion::getPublishedBy, userId)
                .set(ActThemeVersion::getUpdatedAt, now));
        themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .set(ActTheme::getWorkingVersionNo, versionNo)
                .set(ActTheme::getPublishedVersionNo, versionNo)
                .set(ActTheme::getUpdatedAt, now));
        log.info("内容版本发布 themeId={} version={} 操作人={}", themeId, versionNo, userId);
    }

    @Override
    public boolean isPublished(String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        return theme != null && theme.getPublishedVersionNo() != null;
    }

    /** 版本行五表单字段逐字段复制（不含主键/版本号/发布元数据） */
    private void copyFormFields(ActThemeVersion source, ActThemeVersion target) {
        target.setThemeName(source.getThemeName());
        target.setThemeDesc(source.getThemeDesc());
        target.setOwnerUserId(source.getOwnerUserId());
        target.setPlanStartTime(source.getPlanStartTime());
        target.setPlanEndTime(source.getPlanEndTime());
        target.setTotalDrillCount(source.getTotalDrillCount());
        target.setPerSceneLimit(source.getPerSceneLimit());
        target.setJoinMode(source.getJoinMode());
        target.setGuestAccessEnabled(source.getGuestAccessEnabled());
        target.setAiResponseMode(source.getAiResponseMode());
        target.setTrainingModeEnabled(source.getTrainingModeEnabled());
        target.setAssessmentModeEnabled(source.getAssessmentModeEnabled());
        target.setCoachReviewEnabled(source.getCoachReviewEnabled());
        target.setSuggestionEnabled(source.getSuggestionEnabled());
        target.setPracticeMethods(source.getPracticeMethods());
        target.setDifficulty(source.getDifficulty());
        target.setAllowCustomDifficulty(source.getAllowCustomDifficulty());
        target.setFaceCheckEnabled(source.getFaceCheckEnabled());
        target.setFaceCheckStage(source.getFaceCheckStage());
        target.setScreenSwitchMode(source.getScreenSwitchMode());
        target.setScreenSwitchLimit(source.getScreenSwitchLimit());
        target.setScenePassType(source.getScenePassType());
        target.setScenePassCount(source.getScenePassCount());
        target.setUpdatedAt(LocalDateTime.now());
    }

    private ActThemeVersion copyVersionRow(ActThemeVersion source, int versionNo) {
        ActThemeVersion copy = new ActThemeVersion();
        copy.setThemeId(source.getThemeId());
        copy.setVersionNo(versionNo);
        copyFormFields(source, copy);
        copy.setCreator(source.getCreator());
        copy.setUsedUserCount(0);
        copy.setCompletedCount(0);
        copy.setInProgressCount(0);
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedAt(LocalDateTime.now());
        return copy;
    }

    private void copyCases(String themeId, int fromVersion, int toVersion) {
        caseScriptMapper.delete(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getVersionNo, toVersion));
        for (ActCaseScript source : caseScriptMapper.selectList(new LambdaQueryWrapper<ActCaseScript>()
                .eq(ActCaseScript::getThemeId, themeId)
                .eq(ActCaseScript::getVersionNo, fromVersion))) {
            ActCaseScript copy = copyOf(source);
            copy.setVersionNo(toVersion);
            // 新版本行是"待编译"状态：发布初始化按本版本的案例重新编译
            copy.setStatus("draft");
            copy.setCompileError(null);
            copy.setCreatedAt(LocalDateTime.now());
            copy.setUpdatedAt(LocalDateTime.now());
            caseScriptMapper.insert(copy);
        }
    }

    private void copyProfiles(String themeId, int fromVersion, int toVersion) {
        profileMapper.delete(new LambdaQueryWrapper<ActCustomerProfile>()
                .eq(ActCustomerProfile::getThemeId, themeId)
                .eq(ActCustomerProfile::getVersionNo, toVersion));
        for (ActCustomerProfile source : profileMapper.selectList(new LambdaQueryWrapper<ActCustomerProfile>()
                .eq(ActCustomerProfile::getThemeId, themeId)
                .eq(ActCustomerProfile::getVersionNo, fromVersion))) {
            ActCustomerProfile copy = new ActCustomerProfile();
            copy.setThemeId(source.getThemeId());
            copy.setVersionNo(toVersion);
            copy.setProfileType(source.getProfileType());
            copy.setDescription(source.getDescription());
            copy.setPersonalities(source.getPersonalities());
            copy.setDecisionFactors(source.getDecisionFactors());
            copy.setCommunicationStyle(source.getCommunicationStyle());
            copy.setPainPoints(source.getPainPoints());
            copy.setSortOrder(source.getSortOrder());
            copy.setCreatedAt(LocalDateTime.now());
            profileMapper.insert(copy);
        }
    }

    /**
     * 产品档案 + 场景关联 → 发布版本快照（契约 09 §4.2/§4.3）。
     * <p>
     * <b>为什么必须有这一步</b>：act_product_profile 用 content_version_no 区分「工作区(0)」与
     * 「发布快照(&gt;0)」，学员端只读 &gt;0 的行（ProductProfileServiceImpl.studentProfile）。
     * 但原发布流程只复制场景/维度/案例/客户画像/材料，从未复制产品档案——结果是已发布主题的
     * 学员端产品档案永远为空，F08/F09 的档案分区只能靠查询侧回退 v0 兜底。
     * <p>
     * <b>场景关联的版本归属（重要）</b>：act_scenario_product_profile 只有
     * (scenario_unit_id, product_profile_id, sort_order)，<b>没有 content_version_no</b>，
     * 它靠 scenario_unit_id 指向 act_scenario_unit 行来归版本——而场景单元在每次升版/发布时
     * 都会被复制成新 ID 的行。所以"复制场景关联"= 按 sort_order 建立
     * 「源版本单元行 → 目标版本单元行」映射，把关联从源单元 ID 重新挂到目标单元 ID 上。
     * 这里不改表结构（现有列已能表达版本归属），符合任务约束。
     * <p>
     * 幂等：先删目标版本的档案行与目标单元的关联行，再插。重复发布只会得到同一份快照。
     *
     * @param fromVersion 源版本号（首次发布是工作区 0，AI 确认升版是工作区内容，也传 0）
     * @param toVersion   目标发布版本号
     */
    private void copyProductProfiles(String themeId, int fromVersion, int toVersion) {
        // 1. 目标版本档案行先删（uk_product_profile_version_key 是 theme+version+key，先删才不撞唯一键）
        productProfileMapper.delete(new LambdaQueryWrapper<ActProductProfile>()
                .eq(ActProductProfile::getThemeId, themeId)
                .eq(ActProductProfile::getContentVersionNo, toVersion));

        List<ActProductProfile> sources = productProfileMapper.selectList(
                new LambdaQueryWrapper<ActProductProfile>()
                        .eq(ActProductProfile::getThemeId, themeId)
                        .eq(ActProductProfile::getContentVersionNo, fromVersion)
                        .orderByAsc(ActProductProfile::getSortOrder));
        if (sources.isEmpty()) {
            // 无档案是合法状态（老主题/尚未建档案）：不再往下建关联，也不报错
            log.info("发布无产品档案可复制 themeId={} from=v{} to=v{}", themeId, fromVersion, toVersion);
            return;
        }

        // 2. 逐字段复制；记录「源档案 ID → 新档案 ID」，供第 3 步重挂关联
        Map<String, String> newIdByOldId = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (ActProductProfile source : sources) {
            ActProductProfile copy = new ActProductProfile();
            copy.setThemeId(themeId);
            copy.setContentVersionNo(toVersion);
            copy.setProfileKey(source.getProfileKey());
            copy.setProfileName(source.getProfileName());
            copy.setCategory(source.getCategory());
            copy.setDescription(source.getDescription());
            copy.setDetailMarkdown(source.getDetailMarkdown());
            copy.setCustomFields(source.getCustomFields());
            copy.setSourceArtifactId(source.getSourceArtifactId());
            copy.setSourceArtifactVersion(source.getSourceArtifactVersion());
            copy.setSortOrder(source.getSortOrder());
            copy.setCreatedBy(source.getCreatedBy());
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            // insert 后 MyBatis-Plus 回填新雪花 ID（与 ScenarioUnitServiceImpl 同款用法）
            productProfileMapper.insert(copy);
            newIdByOldId.put(source.getId(), copy.getId());
        }

        // 3. 场景关联重挂：源单元行 → 目标单元行（按 sort_order 对齐，与 PublishInitServiceImpl 同口径）
        Map<String, String> targetUnitIdBySortOrder = new HashMap<>();
        for (ActScenarioUnit unit : unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, toVersion))) {
            targetUnitIdBySortOrder.put(String.valueOf(unit.getSortOrder()), unit.getId());
        }
        List<ActScenarioUnit> sourceUnits = unitMapper.selectList(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, fromVersion));
        if (sourceUnits.isEmpty()) {
            log.warn("源版本无场景单元，产品档案关联无处重挂 themeId={} from=v{}", themeId, fromVersion);
            return;
        }

        // 目标单元的旧关联先清（同一目标单元行可能被重复发布反复挂）
        List<String> targetUnitIds = new ArrayList<>(targetUnitIdBySortOrder.values());
        if (!targetUnitIds.isEmpty()) {
            scenarioProductProfileMapper.delete(new LambdaQueryWrapper<ActScenarioProductProfile>()
                    .in(ActScenarioProductProfile::getScenarioUnitId, targetUnitIds));
        }
        // 源关联一次批量取（按单元 ID in），再在内存按单元分组：避免每个场景单元各查一次（N+1）
        List<String> sourceUnitIds = sourceUnits.stream().map(ActScenarioUnit::getId).toList();
        Map<String, List<ActScenarioProductProfile>> linksBySourceUnit = new HashMap<>();
        for (ActScenarioProductProfile link : scenarioProductProfileMapper.selectList(
                new LambdaQueryWrapper<ActScenarioProductProfile>()
                        .in(ActScenarioProductProfile::getScenarioUnitId, sourceUnitIds)
                        .orderByAsc(ActScenarioProductProfile::getSortOrder))) {
            linksBySourceUnit
                    .computeIfAbsent(link.getScenarioUnitId(), key -> new ArrayList<>())
                    .add(link);
        }
        for (ActScenarioUnit sourceUnit : sourceUnits) {
            String targetUnitId = targetUnitIdBySortOrder.get(String.valueOf(sourceUnit.getSortOrder()));
            if (targetUnitId == null) {
                continue;
            }
            for (ActScenarioProductProfile link : linksBySourceUnit.getOrDefault(
                    sourceUnit.getId(), List.of())) {
                String newProfileId = newIdByOldId.get(link.getProductProfileId());
                if (newProfileId == null) {
                    // 关联指向的档案不在源版本里（脏数据）：跳过，不凭空造档案
                    continue;
                }
                ActScenarioProductProfile copy = new ActScenarioProductProfile();
                copy.setScenarioUnitId(targetUnitId);
                copy.setProductProfileId(newProfileId);
                copy.setSortOrder(link.getSortOrder());
                scenarioProductProfileMapper.insert(copy);
            }
        }
        log.info("产品档案发布快照完成 themeId={} from=v{} to=v{} 档案数={} 单元映射数={}",
                themeId, fromVersion, toVersion, sources.size(), targetUnitIdBySortOrder.size());
    }

    /**
     * 学员可见材料 → 发布快照（契约 11 §8）。
     * <p>
     * 材料在 act_ai_material 是主题级、不随内容版本存；学员端 viewer-context 与主题详情
     * 都按「当前发布版本的 act_content_version_material」鉴权和展示，所以每次发布都必须
     * 把当时学员可见的材料固化一份。先删目标版本再插，保证重复发布幂等。
     */
    private void copyMaterials(String themeId, int toVersion) {
        materialSnapshotMapper.delete(new LambdaQueryWrapper<ActContentVersionMaterial>()
                .eq(ActContentVersionMaterial::getThemeId, themeId)
                .eq(ActContentVersionMaterial::getContentVersionNo, toVersion));
        int order = 1;
        for (ActAiMaterial source : materialMapper.selectList(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getThemeId, themeId)
                .eq(ActAiMaterial::getVisibility, VISIBILITY_STUDENT)
                .orderByAsc(ActAiMaterial::getCreatedAt))) {
            ActContentVersionMaterial copy = new ActContentVersionMaterial();
            copy.setThemeId(themeId);
            copy.setContentVersionNo(toVersion);
            copy.setMaterialId(source.getId());
            copy.setName(source.getName());
            copy.setFileType(source.getFileType());
            copy.setFileSize(source.getFileSize());
            // 在线课堂材料以 fileId 前缀区分（本工程既有口径，见 AssistantMaterialServiceImpl）
            boolean fromKng = source.getFileId() != null && source.getFileId().startsWith(KNG_FILE_ID_PREFIX);
            copy.setSourceType(fromKng ? "online_classroom" : "act_local");
            copy.setSourceId(fromKng ? source.getFileId().substring(KNG_FILE_ID_PREFIX.length()) : null);
            copy.setPreviewFileId(source.getPreviewFileId());
            // PDF 且已转换才有版式预览，其余按文本回退（契约 11 §7）
            copy.setRenderMode(source.getPreviewFileId() != null ? "pdf" : "text");
            copy.setVisibility("student");
            copy.setSortOrder(order++);
            copy.setCreatedAt(LocalDateTime.now());
            materialSnapshotMapper.insert(copy);
        }
    }

    /** 案例行复制（显式 setter，编译状态重置为待编译） */
    private ActCaseScript copyOf(ActCaseScript source) {
        ActCaseScript copy = new ActCaseScript();
        copy.setThemeId(source.getThemeId());
        copy.setScenarioUnitId(source.getScenarioUnitId());
        copy.setScriptName(source.getScriptName());
        copy.setDifficulty(source.getDifficulty());
        copy.setActorName(source.getActorName());
        copy.setActorPosition(source.getActorPosition());
        copy.setActorCompany(source.getActorCompany());
        copy.setActorType(source.getActorType());
        copy.setActorPersonality(source.getActorPersonality());
        copy.setActorCommunicationStyle(source.getActorCommunicationStyle());
        copy.setScriptBackground(source.getScriptBackground());
        copy.setScriptOpening(source.getScriptOpening());
        // userRole 三列随行复制（2026-11-05）：升版不能把学员角色弄丢，否则新版本案例
        // 只剩 actor 侧字段、发布快照与运行时投影都读不到 userRole
        copy.setUserRoleName(source.getUserRoleName());
        copy.setUserRolePosition(source.getUserRolePosition());
        copy.setUserRoleBackground(source.getUserRoleBackground());
        return copy;
    }

    private ActTheme requireTheme(String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new IllegalStateException("主题不存在：" + themeId);
        }
        return theme;
    }

    private ActThemeVersion requireVersion(String themeId, Integer versionNo) {
        ActThemeVersion version = versionMapper.selectOne(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, themeId)
                .eq(ActThemeVersion::getVersionNo, versionNo)
                .last("LIMIT 1"));
        if (version == null) {
            throw new IllegalStateException("内容版本不存在 themeId=" + themeId + " version=" + versionNo);
        }
        return version;
    }

    private int maxVersionNo(String themeId) {
        var top = versionMapper.selectList(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, themeId)
                .orderByDesc(ActThemeVersion::getVersionNo)
                .last("LIMIT 1"));
        return top.isEmpty() ? 0 : top.get(0).getVersionNo();
    }
}
