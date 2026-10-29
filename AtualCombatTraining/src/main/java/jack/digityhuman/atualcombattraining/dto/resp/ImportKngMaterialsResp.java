package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入在线课堂培训材料响应（03-接口文档 §3 API-04）
 * <p>
 * 单向逐条处理：成功的进 {@code materials}，失败的进 {@code failures}，
 * 单条失败不阻塞其他课件（一张损坏的 PDF 不该让另外四个材料白导）。
 */
@Data
public class ImportKngMaterialsResp {

    private List<Material> materials = new ArrayList<>();
    private List<Failure> failures = new ArrayList<>();

    /** 导入成功的材料（已落 act_ai_material，有主题时同步进 artifact.materials） */
    @Data
    public static class Material {
        private String id;
        /** kng_<coursewareId>：与本地文件按前缀区分，同会话 upsert 的键 */
        private String fileId;
        private String name;
        private String fileType;
        private Long fileSize;
        /** 3 = 在线课堂（2 为本地文件） */
        private Integer fileSource;
        /** 在线课堂 coursewareId */
        private String sourceId;
        private Integer visibility;
    }

    /** 单条失败原因（中文，可直接展示给用户） */
    @Data
    public static class Failure {
        private String coursewareId;
        private String reason;

        public static Failure of(String coursewareId, String reason) {
            Failure failure = new Failure();
            failure.setCoursewareId(coursewareId);
            failure.setReason(reason);
            return failure;
        }
    }
}
