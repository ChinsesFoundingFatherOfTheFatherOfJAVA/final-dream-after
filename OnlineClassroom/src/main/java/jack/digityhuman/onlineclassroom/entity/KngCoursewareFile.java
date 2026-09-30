package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kng_courseware_file")
public class KngCoursewareFile {

    @TableId(type = IdType.INPUT)
    private String id;
    private String coursewareId;
    private String fileName;
    private String storagePath;
    private Long fileSize;
    private String language;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
