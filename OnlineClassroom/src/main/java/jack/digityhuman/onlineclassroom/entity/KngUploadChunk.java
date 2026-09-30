package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kng_upload_chunk")
public class KngUploadChunk {

    private String uploadId;
    private Integer chunkIndex;
    private Integer totalChunks;
    private String fileName;
    private Long fileSize;
    private String md5;
    private String relDir;
    private LocalDateTime createdAt;
}
