package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学员练习历程页（契约 04 §API-S07 / 01 §F20）。
 * <p>
 * 一次返回四块内容，前端不用连打三个接口：
 * <ul>
 *   <li>stats：四项统计（总练习次数、累计用时、平均得分、覆盖主题）+ 三个 Tab 计数</li>
 *   <li>themes：所属主题下拉的可选项，只列有记录的主题</li>
 *   <li>records/total：当前筛选条件下的分页记录（服务端分页）</li>
 * </ul>
 * 统计口径固定为「该学员全部 attempt」，不随筛选变化，否则 Tab 上的计数会自己跳。
 */
@Data
public class StudentPracticeRecordsResp {

    private Stats stats;
    private List<ThemeOption> themes;
    private List<StudentHistoryResp> records;
    private long total;

    @Data
    public static class Stats {
        private long totalCount;
        /** 累计用时（小时，两位小数） */
        private BigDecimal totalHours;
        /** 平均得分（只统计有报告的记录，无报告时为 null） */
        private BigDecimal averageScore;
        private long themeCount;
        private long passedCount;
        private long failedCount;
        private long inProgressCount;
    }

    @Data
    public static class ThemeOption {
        private String themeId;
        private String themeName;
    }
}
