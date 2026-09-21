package jack.digityhuman.atualcombattraining.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 材料规则摘要单测（FP-42：&gt;3 万字截断 + 首尾拼接，零 LLM 成本）。
 */
class MaterialTextSummarizerTest {

    /** 生成指定长度的可读文本（按序编号，便于断言首尾） */
    private String text(int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append("段").append(builder.length()).append("内容；");
        }
        return builder.substring(0, length);
    }

    @Test
    void 未超过阈值时原样返回() {
        String source = text(MaterialTextSummarizer.TRUNCATE_THRESHOLD);

        String summary = MaterialTextSummarizer.summarize(source);

        assertEquals(source, summary);
        assertFalse(MaterialTextSummarizer.truncated(source));
    }

    @Test
    void 超过阈值时保留首尾各一万五千字并插入省略标记() {
        String source = text(MaterialTextSummarizer.TRUNCATE_THRESHOLD + 5_000);

        String summary = MaterialTextSummarizer.summarize(source);

        assertTrue(MaterialTextSummarizer.truncated(source));
        assertTrue(summary.contains("中间内容过长已省略"));
        assertTrue(summary.startsWith(source.substring(0, 100)), "应保留首部内容");
        assertTrue(summary.endsWith(source.substring(source.length() - 100)), "应保留尾部内容");
        assertEquals(MaterialTextSummarizer.HEAD_CHARS + MaterialTextSummarizer.TAIL_CHARS
                        + MaterialTextSummarizer.OMIT_MARK.length(),
                summary.length(), "摘要长度 = 首部 + 尾部 + 标记");
    }

    @Test
    void 空文本返回空串() {
        assertEquals("", MaterialTextSummarizer.summarize(null));
        assertEquals("", MaterialTextSummarizer.summarize("   "));
    }

    @Test
    void 去掉首尾空白() {
        assertEquals("订单运营规范", MaterialTextSummarizer.summarize("  订单运营规范 \n"));
    }

    @Test
    void 预览文案折叠空白并截断() {
        String summary = "第一行\n第二行\t第三行";

        String preview = MaterialTextSummarizer.preview(summary, 100);
        assertEquals("第一行 第二行 第三行", preview);

        String shortPreview = MaterialTextSummarizer.preview(text(500), 20);
        assertEquals(21, shortPreview.length(), "截断后带省略号");
        assertTrue(shortPreview.endsWith("…"));
    }

    @Test
    void 空摘要预览为空串() {
        assertEquals("", MaterialTextSummarizer.preview(null, 10));
        assertEquals("", MaterialTextSummarizer.preview("  ", 10));
    }
}