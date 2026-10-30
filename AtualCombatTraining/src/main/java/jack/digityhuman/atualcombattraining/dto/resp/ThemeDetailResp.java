package jack.digityhuman.atualcombattraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主题详情响应（FP-07/08，头部信息+统计占位+实时计数）
 */
@Data
public class ThemeDetailResp {

    private String id;
    private String themeName;
    private String themeDesc;
    /** 发布状态 */
    private Integer status;
    /** 主题模式 */
    private Integer themeMode;
    /** 分配模式：1指定分配 2全体学员 */
    private Integer assignMode;
    /** 难度：1简单 2正常 3困难 */
    private Integer difficulty;
    /** 当前版本号（字符串序列化防前端精度丢失） */
    private String currentVersionNo;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
    /** 四统计板块（读主题表占位字段，本次不做真实统计） */
    private Stats stats;
    /** 当前版本场景单元数（实时 count） */
    private Integer scenarioUnitCount;
    /** 版本总数（供「版本历史 (N)」Tab 标） */
    private Integer versionCount;

    /** 占位统计分组 */
    @Data
    public static class Stats {
        /** 通过率(%) */
        private BigDecimal passRate;
        private Integer passUserCount;
        private Integer participantCount;
        private BigDecimal avgScore;
        private Integer totalTrainingCount;
        /** 练习总次数（训练 + 考核，来自报告） */
        private Integer totalPracticeCount;
        /** 训练次数 / 考核次数（按报告 studyMode 拆分） */
        private Integer trainingCount;
        private Integer examCount;
        /** 训练均分 / 考核均分（只统计已生成报告） */
        private BigDecimal trainingAverage;
        private BigDecimal examAverage;
    }
}
