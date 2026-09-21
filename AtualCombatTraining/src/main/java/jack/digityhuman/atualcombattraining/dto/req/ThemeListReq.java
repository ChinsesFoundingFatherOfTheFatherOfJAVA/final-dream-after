package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * 主题列表查询请求（FP-06）
 * jurisdictionType / sourceType 为占位字段：前端本次不传值，但后端实体必须存在，供后续迭代启用。
 */
@Data
public class ThemeListReq {

    /** 页码，从 1 起 */
    private Integer page = 1;
    /** 每页条数，默认 10 */
    private Integer pageSize = 10;
    /** 发布状态：0草稿 1发布中 2发布失败 3已发布 4已暂停 5已结束；不传=全部 */
    private Integer status;
    /** 主题模式：1日常训练 2通关考核 3训考一体；不传=全部 */
    private Integer themeMode;
    /** 主题名称模糊搜索关键词 */
    private String keyword;
    /** 占位：归属 1我管辖的 2我负责的（本次不参与过滤） */
    private Integer jurisdictionType;
    /** 占位：来源 1单独安排 2项目安排（本次不参与过滤） */
    private Integer sourceType;
}
