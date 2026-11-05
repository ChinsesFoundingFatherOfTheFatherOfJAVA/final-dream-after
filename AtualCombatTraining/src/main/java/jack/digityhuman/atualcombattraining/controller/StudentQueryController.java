package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.dto.resp.ReportResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHistoryResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeSummaryResp;
import jack.digityhuman.atualcombattraining.dto.resp.RandomScriptResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentPracticeRecordsResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentScenarioDetailResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentThemeDetailResp;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActReportDimension;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActReportDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.service.ProductProfileService;
import jack.digityhuman.atualcombattraining.service.StudentQueryService;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学员端只读接口（/api/act/student，契约 04 §首页/主题详情/练习历程/报告）
 * <p>
 * 全部带用户归属过滤（越权防护不依赖前端）；JWT 复用 StaffManagement 签发。
 */
@RestController
@RequestMapping("/api/act/student")
@RequiredArgsConstructor
public class StudentQueryController {

    private final StudentQueryService studentQueryService;
    private final CurrentUserService currentUserService;
    private final ActPracticeAttemptMapper attemptMapper;
    private final ActPracticeReportMapper reportMapper;
    private final ActReportDimensionMapper dimensionMapper;
    private final ActThemeMapper themeMapper;
    private final ActScenarioUnitMapper unitMapper;
    private final ProductProfileService productProfileService;

    /** 学员端产品档案详情（契约 09 §7）：只读发布快照 + 参与关系校验（跨主题拒绝） */
    @GetMapping("/product-profiles/{profileId}")
    public ApiResponse<ActProductProfile> productProfile(
            @PathVariable String profileId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                productProfileService.studentProfile(requireUser(authorization), profileId));
    }

    /**
     * 首页聚合（契约 04 §3 API-S01）：一次给全整页数据。
     * <p>
     * 原平台首页三栏（左：头像/段位/四宫格统计，中：快捷入口 + 我的训练主题，右：最近练习）
     * 需要的所有数据都在这里返回，前端一次请求渲染整页；分块接口仍保留给局部刷新。
     */
    @GetMapping("/home/summary")
    public ApiResponse<StudentHomeSummaryResp> homeSummary(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentQueryService.homeSummary(requireUser(authorization)));
    }

    /**
     * 加入公开主题（契约 04 §3 API-S03）：幂等，重复加入不报错。
     */
    @PostMapping("/themes/{themeId}/join")
    public ApiResponse<Void> joinTheme(
            @PathVariable String themeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        studentQueryService.joinTheme(requireUser(authorization), themeId);
        return ApiResponse.success(null);
    }

    /** 首页：我参加的主题卡片 */
    @GetMapping("/home")
    public ApiResponse<StudentHomeResp> home(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentQueryService.home(requireUser(authorization)));
    }


    /** 更多学习机会（AC-03）：公开未加入且计划期内的已发布主题 */
    @GetMapping("/opportunities")
    public ApiResponse<java.util.List<StudentHomeResp.Card>> opportunities(
            @RequestParam(defaultValue = "6") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                studentQueryService.opportunities(requireUser(authorization), Math.min(limit, 12)));
    }
    /** 主题详情：场景 + 材料 + 我的统计 */
    @GetMapping("/themes/{themeId}")
    public ApiResponse<StudentThemeDetailResp> themeDetail(
            @PathVariable String themeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                studentQueryService.themeDetail(requireUser(authorization), themeId));
    }

    /**
     * 场景准备详情（契约 04 §3 API-S05 / 01 §F09）：F09 弹窗九类信息一次给全。
     * <p>
     * 路径与契约原文一致（`/student/themes/{themeId}/scenarios/{scenarioId}`），
     * 本类前缀是 `/api/act`，所以完整路径为
     * `GET /api/act/student/themes/{themeId}/scenarios/{scenarioId}`。
     * 鉴权沿用既有口径（CurrentUserService 取当前用户 + 服务层校验参与关系与发布版本）。
     */
    @GetMapping("/themes/{themeId}/scenarios/{scenarioId}")
    public ApiResponse<StudentScenarioDetailResp> scenarioDetail(
            @PathVariable String themeId,
            @PathVariable String scenarioId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                studentQueryService.scenarioDetail(requireUser(authorization), themeId, scenarioId));
    }

    /**
     * 随机练习取档（契约 08 §4 随机分支，2026-11-04 新增）：从发布期预生成的
     * 「场景 × 难度档」候选中随机取一份脚本，<b>不调模型</b>。
     * <p>
     * 与原平台一致：原平台 `randomScript?difficulty=N` 也是取档接口，产物在发布窗口内已生成。
     * 该接口只读，不创建 attempt；「再练一次」不走这里（走快照复用，保证不换脸）。
     */
    @GetMapping("/themes/{themeId}/scenarios/{scenarioId}/random-script")
    public ApiResponse<RandomScriptResp> randomScript(
            @PathVariable String themeId,
            @PathVariable String scenarioId,
            @RequestParam(defaultValue = "2") int difficulty,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                studentQueryService.randomScript(requireUser(authorization), themeId, scenarioId, difficulty));
    }

    /** 练习历程：attempt 流水分页（含报告摘要） */
    @GetMapping("/history")
    public ApiResponse<PageResult<StudentHistoryResp>> history(
            @RequestParam(required = false) String themeId,
            @RequestParam(required = false) Integer studyMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentQueryService.history(
                requireUser(authorization), themeId, studyMode, page, Math.min(size, 50)));
    }

    /**
     * 练习历程页整页数据（契约 04 §API-S07）：四项统计 + 三 Tab 计数 + 主题下拉 + 分页记录。
     * <p>
     * 注意 pageSize 上限 50（既有 /history 口径一致），契约默认 20 由前端传。
     */
    @GetMapping("/practice-records")
    public ApiResponse<StudentPracticeRecordsResp> practiceRecords(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String themeId,
            @RequestParam(required = false) Integer studyMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(studentQueryService.practiceRecords(
                requireUser(authorization), status, themeId, studyMode,
                page, Math.min(pageSize, 50)));
    }

    /** 报告详情：维度明细/建议/关键对话/推荐（归属校验经 attempt） */
    @GetMapping("/attempts/{attemptId}/report")
    public ApiResponse<ReportResp> report(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        ActPracticeAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !userId.equals(attempt.getUserId())) {
            throw new BizException("无权访问该练习");
        }
        ActPracticeReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getAttemptId, attemptId)
                        .last("LIMIT 1"));
        if (report == null) {
            throw new BizException("报告尚未生成");
        }
        List<ActReportDimension> dimensions = dimensionMapper.selectList(
                new LambdaQueryWrapper<ActReportDimension>()
                        .eq(ActReportDimension::getReportId, report.getId())
                        .orderByAsc(ActReportDimension::getSortOrder));
        ReportResp resp = ReportResp.of(report, dimensions);
        fillReportHeader(resp, attempt, authorization);
        return ApiResponse.success(resp);
    }

    /**
     * 报告页头部元信息（契约 01 §F18）：主题名、场景名、学员姓名 + 脚本快照里的场景旁白与角色名。
     * <p>
     * 学员姓名取自 JWT name claim（不读 sys_user，符合 AGENTS 铁律 1）；
     * 旁白/角色名取 attempt 冻结的脚本快照（契约 D07：报告必须回放当时的脚本，不读当前版本）。
     */
    private void fillReportHeader(ReportResp resp, ActPracticeAttempt attempt, String authorization) {
        resp.setStudentName(currentUserService.userName(authorization));
        ActTheme theme = themeMapper.selectById(attempt.getThemeId());
        if (theme != null) {
            resp.setThemeName(theme.getThemeName());
        }
        ActScenarioUnit unit = unitMapper.selectById(attempt.getScenarioId());
        if (unit != null) {
            resp.setScenarioName(unit.getUnitName());
        }
        ObjectNode script = JsonSupport.obj(attempt.getScriptSnapshotJson());
        if (script == null) {
            return;
        }
        // 脚本快照有两种形态：案例练习存的是 AI 案例正文的扁平字段（actorName/scriptBackground），
        // 随机与自定义练习存的是 drill-script-v1（scene.narration / actor.name / userRole.name）。
        // 两种都要能读，否则报告页的角色名与场景旁白会空白。
        resp.setSceneNarration(firstText(script, script.path("scene").path("narration"), "scriptBackground"));
        resp.setActorName(firstText(script, script.path("actor").path("name"), "actorName"));
        // 案例侧（case-gen v2，2026-11-05）用的是扁平键 userRoleName；没有就 null，
        // 报告页据此回落「我」，不拿别的字段冒充说话人名
        resp.setUserRoleName(firstText(script, script.path("userRole").path("name"), "userRoleName"));
    }

    /** 优先取嵌套字段（drill-script-v1），缺失时回退扁平字段（案例练习快照） */
    private String firstText(ObjectNode root, JsonNode nested, String flatKey) {
        if (nested != null && nested.isTextual() && !nested.asText().isBlank()) {
            return nested.asText();
        }
        if (flatKey == null) {
            return null;
        }
        String value = JsonSupport.text(root, flatKey);
        return value == null || value.isBlank() ? null : value;
    }

    /** attempt 详情在 StudentAttemptController（/api/act/student/attempts/{id}），此处不再重复提供 */

    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if ("0".equals(userId)) {
            throw new BizException("未获取到登录用户，请重新登录");
        }
        return userId;
    }
}



