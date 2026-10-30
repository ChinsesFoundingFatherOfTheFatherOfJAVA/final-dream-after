package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.llm.LlmClient;
import jack.digityhuman.aigentservice.llm.LlmRequest;
import jack.digityhuman.aigentservice.llm.LlmResponse;
import jack.digityhuman.aigentservice.prompt.PromptProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送模型 schema 的净化单测。
 * <p>
 * 背景：契约 schema 带 uniqueItems（如 drill-script-v1 的 productProfileKeys），
 * Qwen response_format 会以 HTTP 400 invalid_parameter_error 拒绝 array 上的该关键字。
 * 净化只发生在"送模型"这一层，契约副本保持逐字一致，语义约束由 prompt + ACT 校验器兜住。
 */
class WorkflowLlmSchemaSanitizeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 捕获实际发给模型的 schema（prompt 用最小输入渲染，避免依赖完整业务字段） */
    private static final class CapturingLlm implements LlmClient {
        private Map<String, Object> sentSchema;

        @Override
        public LlmResponse complete(LlmRequest request) {
            sentSchema = request.responseSchema();
            return new LlmResponse("{\"ok\":true}", List.of(), null);
        }
    }

    private Map<String, Object> capture(String promptResource, String schemaResource) {
        CapturingLlm llm = new CapturingLlm();
        // 空 inputs：prompt 里所有 {{#start.x#}} 都会抛错，所以先读 prompts 判断是否含引用；
        // 这里直接调用 structured 的 schema 净化路径——用一个无变量引用的最小 prompt 资源不便，
        // 改为通过反射不可取，故用 try/catch：渲染失败时手工复现净化逻辑不可接受。
        // 结论：把净化做成可独立测试的静态方法（见下），这里只做端到端冒烟。
        new WorkflowLlm(new PromptProvider(), MAPPER).structured(
                new WorkflowContext(Map.of(), MAPPER), llm, promptResource, schemaResource);
        return llm.sentSchema;
    }

    @Test
    void 净化方法去掉Qwen不支持的array关键字并保留其他约束() throws Exception {
        String raw = new PromptProvider().load(
                "prompts/workflows/drill-script/drill-script-v1.schema.json");
        Map<String, Object> schema = MAPPER.readValue(raw, new TypeReference<>() {
        });

        Map<String, Object> cleaned = WorkflowLlm.sanitizeSchemaForModel(schema);
        String json = MAPPER.valueToTree(cleaned).toString();

        assertFalse(json.contains("uniqueItems"), "uniqueItems 必须被去掉（否则 Qwen 400）");
        assertFalse(json.contains("minContains"));
        // 其他约束保留（净化不能把 schema 掏空）
        assertTrue(json.contains("schemaVersion"));
        assertTrue(json.contains("maxDurationSeconds"));
        assertTrue(json.contains("productProfileKeys"));
    }

    @Test
    void 净化不改变契约副本文件() {
        // 契约副本仍保留 uniqueItems，证明净化只发生在发送层
        String raw = new PromptProvider().load(
                "prompts/workflows/drill-script/drill-script-v1.schema.json");
        assertTrue(raw.contains("uniqueItems"), "契约副本必须保持逐字一致");
    }

    @Test
    void 产品档案schema同样净化() throws Exception {
        String raw = new PromptProvider().load(
                "prompts/workflows/product-profile/product-profile-v1.schema.json");
        Map<String, Object> schema = MAPPER.readValue(raw, new TypeReference<>() {
        });

        Map<String, Object> cleaned = WorkflowLlm.sanitizeSchemaForModel(schema);
        String json = MAPPER.valueToTree(cleaned).toString();

        assertFalse(json.contains("uniqueItems"));
        assertTrue(json.contains("profileKey"));
    }
}
