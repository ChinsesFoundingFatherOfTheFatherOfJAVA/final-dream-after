package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学员首页聚合响应（契约 04 §3 API-S01）
 * <p>
 * 原平台首页左栏是「头像 + 姓名 + 段位 + 公司 + 四宫格统计」，中栏是
 * 「快捷入口 + 我的训练主题（进度条）」，右栏是「最近练习」。
 * 这里按 API-S01 一次性给出 user / stats / themes / opportunities / recentPractice，
 * 前端一次请求即可渲染整页，不再逐块拼装。
 * <p>
 * 首期口径（契约明确允许）：
 * <ul>
 *   <li>honorScore：按已完成报告总分累加（无报告则为 0），契约说"首期可为固定展示值"；</li>
 *   <li>totalHours：按 ended_at - started_at 累加（秒）/3600，保留 2 位；</li>
 *   <li>departmentName：本工程不引 OrgQueryService，置 null，前端用已有用户信息映射。</li>
 * </ul>
 */
@Data
public class StudentHomeSummaryResp {

    private User user;
    private Stats stats;
    private List<StudentHomeResp.Card> themes;
    private List<StudentHomeResp.Card> opportunities;
    /** 最近 1 条练习（D09：首页只 1 条） */
    private StudentHistoryResp recentPractice;

    @Data
    public static class User {
        private String userId;
        private String name;
        private String departmentName;
        private String positionName;
        private String avatar;
    }

    @Data
    public static class Stats {
        /** 累计学时（小时，2 位小数） */
        private BigDecimal totalHours;
        /** 练习次数（正式扣次口径） */
        private long practiceCount;
        /** 参与主题数 */
        private long themeCount;
        /** 总荣誉分 */
        private int honorScore;
    }
}
