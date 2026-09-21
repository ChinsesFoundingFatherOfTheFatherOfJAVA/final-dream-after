package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练主题实体（表 act_theme，列数=18，与 DDL 逐列对应）
 * <p>
 * 主题表冗余当前版本的可筛选/可展示字段（theme_name/theme_mode/assign_mode/difficulty），
 * 保存设置与回滚时由 ThemeService#syncRedundantFields 统一同步。
 */
@Data
@TableName("act_theme")
public class ActTheme {

    /** 雪花 ID（VARCHAR(32)，前端按字符串处理） */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 主题名称（冗余自当前版本） */
    private String themeName;
    /** 主题描述（冗余自当前版本） */
    private String themeDesc;
    /** 发布状态：0草稿 1发布中 2发布失败 3已发布 4已暂停 5已结束 */
    private Integer status;
    /** 主题模式：1日常训练 2通关考核 3训考一体（由版本演练模式推导冗余） */
    private Integer themeMode;
    /** 分配模式：1指定分配 2全体学员（由版本加入方式推导冗余） */
    private Integer assignMode;
    /** 难度：1简单 2正常 3困难（冗余自当前版本） */
    private Integer difficulty;
    /** 占位：归属 1我管辖的 2我负责的（前端暂不传值） */
    private Integer jurisdictionType;
    /** 占位：来源 1单独安排 2项目安排（前端暂不传值） */
    private Integer sourceType;
    /** 当前版本指针（回滚=仅切此字段） */
    private Integer currentVersionNo;
    /** 占位统计：通过率(%) */
    private BigDecimal passRate;
    /** 占位统计：通过人数 */
    private Integer passUserCount;
    /** 占位统计：参与人数 */
    private Integer participantCount;
    /** 占位统计：平均得分 */
    private BigDecimal avgScore;
    /** 占位统计：训练总次数 */
    private Integer totalTrainingCount;
    /** 占位：客户画像数量（内容设计页展示；AI 确认落库后写真实值） */
    private Integer customerProfileCount;
    /** 绑定的 AI 会话 ID（AI 设计助手路径建草稿时回填，供主题→会话反查） */
    private String conversationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
