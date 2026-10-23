package jack.digityhuman.aigentservice.memory;

import java.util.List;

/** 判断助手文本是否已经交付完整的结构化场景规划。 */
public final class ScenePlanDetector {

    private static final List<String> MARKERS = List.of(
            "场景名称", "核心冲突", "业务目标", "难度", "画像建议", "维度草案");

    private ScenePlanDetector() {
    }

    public static boolean delivered(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : MARKERS) {
            if (!text.contains(marker)) {
                return false;
            }
        }
        return true;
    }
}
