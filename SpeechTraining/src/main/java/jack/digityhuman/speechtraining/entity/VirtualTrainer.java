package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("virtual_trainer")
public class VirtualTrainer {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String trainerName;
    /** 陪练者形象图片 URL（v1.1，AI 生成或上传；空则学员端显示默认占位图） */
    private String avatarImage;
    private String language;
    private String voiceId;
    private Integer speed;
    private String inputLanguage;
    private String forbiddenWords;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
