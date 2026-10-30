package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员端首页响应（契约 04 §首页）
 * <p>
 * 每个主题一张卡片：基础信息 + 模式/练习方式 + 计划时间 + 我的三类统计摘要
 * （训练次数 / 通过场景数 / 考核剩余次数）。
 */
@Data
public class StudentHomeResp {

    private List<Card> themes;

    @Data
    public static class Card {
        private String themeId;
        private String name;
        private String description;
        private Integer trainingModeEnabled;
        private Integer assessmentModeEnabled;
        private String practiceMethods;
        private LocalDateTime planStartTime;
        private LocalDateTime planEndTime;
        private Integer publishedVersionNo;
        /** 我在该主题的训练正式次数 */
        private long myTrainingCount;
        /** 该主题已发布版本的场景总数（首页卡片"3 个场景"） */
        private long scenarioCount;
        /** 进度百分比 0-100（已通过场景 / 场景总数，向下取整） */
        private int progress;
        /** 我已通过的场景数（训练口径） */
        private long myPassedScenarios;
        /** 考核剩余次数（0=不限） */
        private long myExamRemaining;
        /**
         * 主题卡状态（契约 F04）：passed=全部场景通过 / ended=计划已结束 /
         * in_progress=有训练或参与记录 / not_started=未开始。
         * 由服务端统一判定，前端只负责按枚举换文案，避免两端口径漂移。
         */
        private String status;
    }
}
