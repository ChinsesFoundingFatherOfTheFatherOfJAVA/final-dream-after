package jack.digityhuman.atualcombattraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 随机练习脚本（发布期预生成分档，表 act_random_script）。
 * <p>
 * <b>为什么单独建表</b>：随机脚本与案例脚本的生命周期、查询口径完全不同——
 * 案例按「每案例一行、按 case_script_id 对齐」，随机按「场景 × 难度 × 候选序号」分档。
 * 若塞进 act_drill_script 就需要加 kind/difficulty/candidate_no 三列并放宽
 * case_script_id 的 NOT NULL 约束，还要给既有 11 处查询逐一补 `kind='case'` 过滤，
 * 漏一处就会让案例练习取到随机脚本。单独建表后案例链路零改动。
 * <p>
 * <b>与原平台的对应关系</b>（`tmp/investigation-R2R3.md` §4.3）：原平台发布窗口内
 * （70 秒）把 4 场景 × 3 难度 = 12 份随机脚本全部生成完；学员端
 * `randomScript?difficulty=N` 只是取档、不调模型。本表就是这 12 份（× 每档候选数）的落库形态。
 * <p>
 * <b>幂等</b>：唯一键 (theme_id, version_no, scenario_unit_id, difficulty, candidate_no)，
 * 发布重跑时先删该版本再插，不会堆积。
 */
@Data
@TableName("act_random_script")
public class ActRandomScript {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String themeId;
    private Integer versionNo;
    private String scenarioUnitId;
    /** 难度档 1 简单 / 2 中等 / 3 困难 */
    private Integer difficulty;
    /** 同档候选序号（1 起）。同档多份候选随机取一，复刻原平台"每次可能不同"的观感 */
    private Integer candidateNo;
    /** 自包含的 drill-script-v1 JSON（与案例快照同格式，运行时直接消费） */
    private String compiledPayload;
    private LocalDateTime compiledAt;
}
