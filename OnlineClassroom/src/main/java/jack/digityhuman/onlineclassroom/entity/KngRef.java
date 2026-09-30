package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kng_ref")
public class KngRef {

    @TableId(type = IdType.INPUT)
    private String id;
    private String coursewareId;
    private Integer refType;
    private String refId;
    private String refName;
    private LocalDateTime createdAt;
}
