package jack.digityhuman.atualcombattraining.dify;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Dify 流式事件翻译器（FP-34 节流要求）
 * <p>
 * 线上是逐 token 推 part-content（碎片化），本项目按设计有意偏离：把 Dify 的 agent_message 增量
 * 按 80ms 窗口聚合后交给下游一次性输出（下游把它作为一个 text part 推送并计入助手消息内容块）。
 * <p>
 * 每个聚合窗口对应一个独立 part，因此工具端点异步推来的 bizType data part 会自然交织在
 * text part 之间（text→data→text），前端无感。
 * 首个 chunk 立即 flush（保证首字延迟），之后按窗口节流；轮次结束前调用 {@link #flush()} 清空余量。
 * <p>
 * 线程模型：只由 Dify SSE 读取线程调用。时钟可注入以便单测。
 */
public class DifyEventTranslator {

    /** 默认聚合窗口（毫秒） */
    public static final long DEFAULT_THROTTLE_MS = 80L;

    /** 聚合后文本的接收方（通常是当前轮次上下文，负责推帧 + 落库内容块） */
    private final Consumer<String> sink;
    private final long throttleMs;
    private final LongSupplier clock;

    /** 待聚合文本缓冲 */
    private final StringBuilder pending = new StringBuilder();
    /** 上次 flush 时刻（毫秒），初值 0 使首个 chunk 立即 flush */
    private long lastFlushAt;
    /** 已产出的聚合批次（单测断言节流效果） */
    private int partCount;

    public DifyEventTranslator(Consumer<String> sink) {
        this(sink, DEFAULT_THROTTLE_MS, System::currentTimeMillis);
    }

    /** 供单测注入窗口与时钟 */
    public DifyEventTranslator(Consumer<String> sink, long throttleMs, LongSupplier clock) {
        this.sink = sink;
        this.throttleMs = throttleMs;
        this.clock = clock;
    }

    /**
     * 收到一个 Dify 文本增量：入缓冲，达到节流窗口则立即输出。
     */
    public void onChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        pending.append(chunk);
        if (clock.getAsLong() - lastFlushAt >= throttleMs) {
            flush();
        }
    }

    /**
     * 把缓冲中的文本作为一个聚合批次交给下游（下游推 text part 并累计进助手消息内容）。
     */
    public void flush() {
        if (pending.length() == 0) {
            return;
        }
        String text = pending.toString();
        pending.setLength(0);
        sink.accept(text);
        lastFlushAt = clock.getAsLong();
        partCount++;
    }

    /** 已产出的聚合批次数 */
    public int partCount() {
        return partCount;
    }

    /** 是否还有未 flush 的文本 */
    public boolean hasPending() {
        return pending.length() > 0;
    }
}