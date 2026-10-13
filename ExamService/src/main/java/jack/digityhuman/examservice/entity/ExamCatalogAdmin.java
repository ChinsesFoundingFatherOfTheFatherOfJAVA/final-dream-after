package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 分类管理员关系（权限读取时按继承链合并）。 */
@Data
@TableName("exam_catalog_admin")
public class ExamCatalogAdmin {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String catalogId;
    private String userId;
    private LocalDateTime createdAt;

}
