package jack.digityhuman.aigentservice.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolStageGuardTest {

    private final ToolStageGuard guard = new ToolStageGuard();

    @Test
    void allowsCreateTopicOnlyWhenANewSessionHasDirection() {
        assertTrue(guard.check("create_topic", "我想做客户投诉处理训练，目标是提升一线客服能力",
                Map.of("theme_status", "new", "artifact_summary", "")).allowed());
    }

    @Test
    void rejectsCreateTopicForAnExistingThemeUnlessTheUserExplicitlyRenamesIt() {
        assertFalse(guard.check("create_topic", "继续完善现有内容",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景")).allowed());
        assertTrue(guard.check("create_topic", "请把主题改名为《客户投诉处理实战》",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景")).allowed());
    }

    @Test
    void blocksGenerateToolsUntilTheUserExplicitlyConfirms() {
        assertFalse(guard.check("generate_theme", "行吧，先这样",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景规划")).allowed());
        assertTrue(guard.check("generate_theme", "可以，开始生成主题",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景规划")).allowed());
    }

    @Test
    void rejectsAllToolsOnAnEmptySessionStartQuery() {
        assertFalse(guard.check("create_topic", "",
                Map.of("theme_status", "new", "artifact_summary", "")).allowed());
        assertFalse(guard.check("generate_theme", "",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景规划")).allowed());
    }

    @Test
    void updateArtifactRequiresAnExistingTheme() {
        assertFalse(guard.check("update_artifact", "修改材料可见性",
                Map.of("theme_status", "new", "artifact_summary", "")).allowed());
        assertTrue(guard.check("update_artifact", "修改材料可见性",
                Map.of("theme_status", "draft", "artifact_summary", "已有场景")).allowed());
    }

    @Test
    void rejectsCreateTopicWhenTheUserHasNotGivenAConcreteDirection() {
        assertFalse(guard.check("create_topic", "我想做培训，你看着办",
                Map.of("theme_status", "new", "artifact_summary", "")).allowed());
    }

    @Test
    void generateThemeRequiresAThemeAndAPlanDeliveredInConversation() {
        assertFalse(guard.check("generate_theme", "可以，开始生成",
                Map.of("theme_status", "draft", "artifact_summary", "场景单元(0)"), false).allowed());
        assertTrue(guard.check("generate_theme", "可以，开始生成",
                Map.of("theme_status", "draft", "artifact_summary", "场景单元(0)"), true).allowed());
    }

    @Test
    void generateCasesRequiresAtLeastOneExistingScene() {
        assertFalse(guard.check("generate_cases", "可以，生成案例",
                Map.of("theme_status", "draft", "artifact_summary", "场景单元(0)："), true).allowed());
        assertTrue(guard.check("generate_cases", "可以，生成案例",
                Map.of("theme_status", "draft", "artifact_summary", "场景单元(1)：s1 投诉处理（案例 0 个）"), true).allowed());
    }
}
