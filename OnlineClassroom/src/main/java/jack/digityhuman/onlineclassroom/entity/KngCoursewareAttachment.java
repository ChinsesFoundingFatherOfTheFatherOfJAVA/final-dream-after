package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kng_courseware_attachment")
public class KngCoursewareAttachment {

    @TableId(type = IdType.INPUT)
    private String id;
    private String coursewareId;
    private String fileName;
    private String storagePath;
    private Long fileSize;
    private LocalDateTime createdAt;
}
