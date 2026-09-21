package jack.digityhuman.atualcombattraining.dify;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dify 流式事件翻译器单测（FP-34 节流：chunk → 80ms 聚合为一个 text part）。
 * 用可控时钟验证：首个 chunk 立即输出、窗口内多个 chunk 合并、窗口外的 chunk 另起一批、
 * 结尾 flush 清空余量。
 */
class DifyEventTranslatorTest {

    /** 收集聚合批次的输出 */
    private final List<String> batches = new ArrayList<>();

    @Test
    void 首个chunk立即输出() {
        AtomicLong clock = new AtomicLong(1000L);
        DifyEventTranslator translator = new DifyEventTranslator(batches::add, 80L, clock::get);

        translator.onChunk("开始");

        assertEquals(1, batches.size());
        assertEquals("开始", batches.get(0));
        assertFalse(translator.hasPending());
    }

    @Test
    void 窗口内多个chunk合并为一个批次() {
        AtomicLong clock = new AtomicLong(1000L);
        DifyEventTranslator translator = new DifyEventTranslator(batches::add, 80L, clock::get);

        translator.onChunk("A");   // 首个立即输出
        clock.set(1010L);
        translator.onChunk("B");   // 距上次 10ms，未到窗口 → 缓冲
        clock.set(1050L);
        translator.onChunk("C");   // 距上次 50ms，仍未到窗口
        assertEquals(1, batches.size());

        clock.set(1090L);
        translator.onChunk("D");   // 距上次 90ms ≥80ms → 输出 BCD
        assertEquals(2, batches.size());
        assertEquals("BCD", batches.get(1));
    }

    @Test
    void 结尾flush输出余量() {
        AtomicLong clock = new AtomicLong(2000L);
        DifyEventTranslator translator = new DifyEventTranslator(batches::add, 80L, clock::get);

        translator.onChunk("首");
        clock.set(2010L);
        translator.onChunk("尾");
        assertTrue(translator.hasPending());

        translator.flush();

        assertEquals(2, batches.size());
        assertEquals("尾", batches.get(1));
        assertFalse(translator.hasPending());
    }

    @Test
    void 空chunk被忽略且空缓冲不产生批次() {
        AtomicLong clock = new AtomicLong(3000L);
        DifyEventTranslator translator = new DifyEventTranslator(batches::add, 80L, clock::get);

        translator.onChunk(null);
        translator.onChunk("");
        translator.flush();

        assertTrue(batches.isEmpty());
        assertEquals(0, translator.partCount());
    }

    @Test
    void 默认节流窗口为80毫秒() {
        assertEquals(80L, DifyEventTranslator.DEFAULT_THROTTLE_MS);
    }
}