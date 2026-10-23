package jack.digityhuman.aigentservice.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowContextTest {

    @Test
    void rendersStartInputsAndNodeOutputsUsingDslReferences() {
        WorkflowContext context = new WorkflowContext(Map.of(
                "goal", "提升投诉处理能力",
                "audiences", "[\"一线客服\"]"
        ), new ObjectMapper());
        context.putNode("code_scenes", Map.of(
                "scenes_json", "[{\"id\":\"sc-01\"}]",
                "scene_count", 1));

        String rendered = context.render("""
                目标：{{#start.goal#}}
                人群：{{#start.audiences#}}
                场景：{{#code_scenes.scenes_json#}}
                数量：{{#code_scenes.scene_count#}}
                """);

        assertEquals("""
                目标：提升投诉处理能力
                人群：["一线客服"]
                场景：[{"id":"sc-01"}]
                数量：1
                """, rendered);
    }

    @Test
    void serializesObjectAndArrayNodeValuesAsJsonStrings() {
        WorkflowContext context = new WorkflowContext(Map.of(), new ObjectMapper());
        context.putNode("llm", Map.of(
                "items", List.of("a", "b"),
                "meta", Map.of("pass", true)));

        assertEquals("[\"a\",\"b\"] / {\"pass\":true}",
                context.render("{{#llm.items#}} / {{#llm.meta#}}"));
    }

    @Test
    void rejectsMissingVariablesInsteadOfRenderingSilentBlankText() {
        WorkflowContext context = new WorkflowContext(Map.of("goal", "x"), new ObjectMapper());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> context.render("{{#start.missing#}}"));

        assertEquals("工作流变量不存在：start.missing", error.getMessage());
    }
}
