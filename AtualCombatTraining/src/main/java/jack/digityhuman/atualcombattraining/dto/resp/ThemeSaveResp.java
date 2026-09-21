package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 保存主题设置响应（FP-18）
 */
@Data
public class ThemeSaveResp {

    private String id;
    /** 新版本号（字符串序列化） */
    private String versionNo;
    /** 由本次演练模式推导的主题模式：1日常训练 2通关考核 3训考一体 */
    private Integer themeMode;
    /** 由本次加入方式推导的分配模式：1指定分配 2全体学员 */
    private Integer assignMode;
}
