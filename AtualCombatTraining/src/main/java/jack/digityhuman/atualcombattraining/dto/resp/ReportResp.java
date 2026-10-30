package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练报告响应（契约 04 §报告页）
 * <p>
 * 由 act_practice_report + act_report_dimension 组装；improvement/keyDialogues/recommendations
 * 是生成时的 JSON 原样回显（结构见 drill-report-v1）。
 */
@Data
public class ReportResp {

    private String reportId;
    private String attemptId;
    private String themeId;
    private String scenarioId;
    private Integer studyMode;
    private String practiceType;
    private Integer passed;
    private Integer targetReached;
    private String targetReason;
    private Integer timedOut;
    private String endReason;
    private BigDecimal totalScore;
    private Integer passScore;
    private Integer durationSeconds;
    private Integer roundCount;
    private String summary;
    private Object improvement;
    private Object keyDialogues;
    private Object recommendations;
    private LocalDateTime generatedAt;
    private List<Dimension> dimensions;

    // ===== 报告页头部元信息（契约 01 §F18：场景/主题/学员/完成时间/时长/轮次/模式·类型）=====
    /** 主题名（报告页 hero "训练主题"） */
    private String themeName;
    /** 场景名（报告页标题） */
    private String scenarioName;
    /** 学员姓名（JWT name claim；未取到为 null，前端显示占位） */
    private String studentName;
    /** 场景旁白（脚本快照 scene.narration，报告页"场景"条） */
    private String sceneNarration;
    /** AI 角色名（脚本快照 actor.name，对话回放说话人） */
    private String actorName;
    /** 我方角色名（脚本快照 userRole.name，对话回放说话人） */
    private String userRoleName;
    /** 完整对话回放（transcriptJson 原样解析；[{role,text}]） */
    private Object transcript;

    @Data
    public static class Dimension {
        private String dimensionKey;
        private String dimensionName;
        private BigDecimal score;
        private BigDecimal weight;
        private Object evidence;
        private String strength;
        private String improvement;
        private String reason;
    }

    /** 报告 + 维度 → 响应（JSON 列原样解析为对象/数组） */
    public static ReportResp of(jack.digityhuman.atualcombattraining.entity.ActPracticeReport report,
                                List<jack.digityhuman.atualcombattraining.entity.ActReportDimension> dimensions) {
        ReportResp resp = new ReportResp();
        resp.setReportId(report.getId());
        resp.setAttemptId(report.getAttemptId());
        resp.setThemeId(report.getThemeId());
        resp.setScenarioId(report.getScenarioId());
        resp.setStudyMode(report.getStudyMode());
        resp.setPracticeType(report.getPracticeType());
        resp.setPassed(report.getPassed());
        resp.setTargetReached(report.getTargetReached());
        resp.setTargetReason(report.getTargetReason());
        resp.setTimedOut(report.getTimedOut());
        resp.setEndReason(report.getEndReason());
        resp.setTotalScore(report.getTotalScore());
        resp.setPassScore(report.getPassScore());
        resp.setDurationSeconds(report.getDurationSeconds());
        resp.setRoundCount(report.getRoundCount());
        resp.setSummary(report.getSummary());
        resp.setImprovement(parse(report.getImprovementJson()));
        resp.setKeyDialogues(parse(report.getKeyDialoguesJson()));
        resp.setRecommendations(parse(report.getRecommendationsJson()));
        resp.setGeneratedAt(report.getGeneratedAt());
        resp.setTranscript(parse(report.getTranscriptJson()));
        resp.setDimensions(dimensions.stream().map(dimension -> {
            ReportResp.Dimension item = new ReportResp.Dimension();
            item.setDimensionKey(dimension.getDimensionKey());
            item.setDimensionName(dimension.getDimensionName());
            item.setScore(dimension.getScore());
            item.setWeight(dimension.getWeight());
            item.setEvidence(parse(dimension.getEvidenceJson()));
            item.setStrength(dimension.getStrength());
            item.setImprovement(dimension.getImprovement());
            item.setReason(dimension.getReason());
            return item;
        }).toList());
        return resp;
    }

    /** JSON 字符串 → 对象（不合法时原样返回字符串，前端容错） */
    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
