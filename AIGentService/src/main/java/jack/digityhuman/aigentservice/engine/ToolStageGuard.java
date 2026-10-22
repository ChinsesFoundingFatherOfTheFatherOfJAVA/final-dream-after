package jack.digityhuman.aigentservice.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具阶段守卫。
 *
 * <p>模型偶尔会在错误阶段调用工具，不能只依赖 instruction。这里把会造成业务副作用的
 * 阶段规则放在引擎侧，保证模型误判时工具也不会执行。
 */
public class ToolStageGuard {

    private static final Pattern SCENE_COUNT = Pattern.compile("场景单元\\((\\d+)\\)");
    private static final String[] VAGUE_DIRECTION = {
            "你看着办", "你决定", "随便", "都行", "不知道", "还没想好"
    };
    private static final String[] CONCRETE_DIRECTION = {
            "客户", "投诉", "销售", "客服", "员工", "新员工", "一线", "岗位",
            "合规", "服务", "流程", "沟通", "管理", "目标", "提升", "行业"
    };

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision check(String toolName, String query, Map<String, String> inputs) {
        return check(toolName, query, inputs, false);
    }

    public Decision check(String toolName, String query, Map<String, String> inputs,
                          boolean scenePlanAvailable) {
        return check(toolName, query, inputs, scenePlanAvailable, query);
    }

    public Decision check(String toolName, String query, Map<String, String> inputs,
                          boolean scenePlanAvailable, String conversationContext) {
        String text = query == null ? "" : query.trim();
        Map<String, String> context = inputs == null ? Map.of() : inputs;
        String themeStatus = context.getOrDefault("theme_status", "new");
        String artifactSummary = context.getOrDefault("artifact_summary", "");
        boolean hasTheme = !"new".equalsIgnoreCase(themeStatus)
                || (artifactSummary != null && !artifactSummary.isBlank());
        boolean hasScenePlan = scenePlanAvailable
                || (artifactSummary != null && artifactSummary.contains("场景规划"));
        int sceneCount = sceneCount(artifactSummary);

        if (text.isBlank()) {
            return Decision.deny("会话尚未收到用户输入，本轮不允许调用工具");
        }
        return switch (toolName) {
            case "create_topic" -> createTopicDecision(text, conversationContext, hasTheme);
            case "update_artifact" -> hasTheme
                    ? Decision.allow()
                    : Decision.deny("请先创建主题再更新产物");
            case "generate_theme" -> generateThemeDecision(text, hasTheme, hasScenePlan);
            case "generate_cases" -> generateCasesDecision(text, hasTheme, sceneCount);
            default -> Decision.deny("未知工具：" + toolName);
        };
    }

    private Decision createTopicDecision(String text, String conversationContext, boolean hasTheme) {
        if (!hasTheme) {
            String direction = (conversationContext == null ? "" : conversationContext)
                    + "\n" + text;
            return hasConcreteDirection(direction)
                    ? Decision.allow()
                    : Decision.deny("用户尚未给出明确的主题方向和训练目标");
        }
        return isRenameRequest(text)
                ? Decision.allow()
                : Decision.deny("已有主题，禁止重复创建");
    }

    private Decision generateThemeDecision(String text, boolean hasTheme, boolean hasScenePlan) {
        if (!hasTheme) {
            return Decision.deny("请先创建主题再生成内容");
        }
        if (!explicitConfirmation(text)) {
            return Decision.deny("用户尚未明确确认方案，禁止生成类工具");
        }
        if (!hasScenePlan) {
            return Decision.deny("请先在对话中交付场景规划并取得用户明确确认");
        }
        return Decision.allow();
    }

    private Decision generateCasesDecision(String text, boolean hasTheme, int sceneCount) {
        if (!hasTheme) {
            return Decision.deny("请先创建主题并生成场景");
        }
        if (!explicitConfirmation(text)) {
            return Decision.deny("用户尚未明确确认方案，禁止生成类工具");
        }
        if (sceneCount < 1) {
            return Decision.deny("当前产物没有可用场景，不能生成案例");
        }
        return Decision.allow();
    }

    private boolean explicitConfirmation(String text) {
        return containsAny(text, "可以", "开始生成", "没问题", "就按这个来", "确认",
                "按方案生成", "同意");
    }

    private boolean hasConcreteDirection(String text) {
        if (containsAny(text, VAGUE_DIRECTION)) {
            return false;
        }
        return containsAny(text, CONCRETE_DIRECTION);
    }

    private boolean isRenameRequest(String text) {
        return containsAny(text, "改名", "修改名称", "重命名", "修改主题名");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private int sceneCount(String artifactSummary) {
        if (artifactSummary == null || artifactSummary.isBlank()) {
            return 0;
        }
        Matcher matcher = SCENE_COUNT.matcher(artifactSummary);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
