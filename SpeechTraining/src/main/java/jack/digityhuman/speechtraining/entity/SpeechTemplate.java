package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("speech_template")
public class SpeechTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Integer permission;
    private Integer status;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
