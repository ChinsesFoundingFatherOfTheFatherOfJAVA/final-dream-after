package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * update_artifact 工具请求（03 文档 2.5.2）
 * <p>
 * patches 声明为 Object 而非具体 JSON 树类型：Spring Boot 4 的 HTTP 消息转换器是 Jackson 3
 * （tools.jackson），而本模块内部使用 Jackson 2 树模型，跨边界的 DTO 字段一律用普通 Java 类型
 * （数组会反序列化为 List&lt;Map&gt;），由 JsonSupport 在服务层转成树节点。
 */
@Data
public class ArtifactPatchesReq {

    /** JSON-Patch 数组，op 当前仅 full_replace：{op, path, value, reason} */
    private Object patches;
}