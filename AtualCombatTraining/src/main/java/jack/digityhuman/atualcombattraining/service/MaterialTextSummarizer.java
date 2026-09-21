package jack.digityhuman.atualcombattraining.service;

/**
 * 材料文本 → 摘要（FP-42 规则摘要：&gt;3 万字截断 + 首尾拼接）
 * <p>
 * 纯函数、无依赖、零 LLM 成本（2026-09-18 用户确认的方案）。
 * 摘要用于喂给编排智能体（material_summary）与 act_ai_material.summary 留档。
 */
public final class MaterialTextSummarizer {

    /** 触发截断的字符数阈值（3 万字） */
    public static final int TRUNCATE_THRESHOLD = 30_000;
    /** 截断时保留的首部长度 */
    public static final int HEAD_CHARS = 15_000;
    /** 截断时保留的尾部长度 */
    public static final int TAIL_CHARS = 15_000;
    /** 截断标记（提示模型中间内容被省略） */
    public static final String OMIT_MARK = "\n\n……（中间内容过长已省略，请基于首尾内容与文件名判断主题方向）……\n\n";

    private MaterialTextSummarizer() {
    }

    /**
     * 生成规则摘要：不超过阈值原样返回；超过则保留首尾各 15,000 字并插入省略标记。
     *
     * @param text 提取到的全文（可空）
     * @return 摘要（空文本返回空串）
     */
    public static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.strip();
        if (normalized.length() <= TRUNCATE_THRESHOLD) {
            return normalized;
        }
        String head = normalized.substring(0, HEAD_CHARS);
        String tail = normalized.substring(normalized.length() - TAIL_CHARS);
        return head + OMIT_MARK + tail;
    }

    /** 摘要是否发生了截断（供日志与事件文案使用） */
    public static boolean truncated(String text) {
        return text != null && text.strip().length() > TRUNCATE_THRESHOLD;
    }

    /**
     * 事件/日志用的短文案：取摘要开头若干字（解析完成卡片里内联展示的那句话）。
     */
    public static String preview(String summary, int maxChars) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String flat = summary.replaceAll("\\s+", " ").strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "…";
    }
}