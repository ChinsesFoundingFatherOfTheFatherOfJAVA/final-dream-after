package jack.digityhuman.atualcombattraining.websocket;

import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS 入站派发器单测（2026-09-21 修复"WS 经常 pong 超时"）
 * <p>
 * 要守住的三条性质：
 * 1. 派发即时返回——容器读线程不能被一帧聊天的长阻塞占住，否则该连接的心跳应答没人读；
 * 2. 同一会话严格串行——轮次上下文与工作区写入按串行假设实现；
 * 3. 不同会话互不阻塞——单会话串行不能退化成全局串行。
 */
class WsInboundDispatcherTest {

    private WsInboundDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.destroy();
        }
    }

    /**
     * 按用例需要构造派发器（池大小与单会话排队上限都显式给出）。
     * 排队上限若比用例的帧数小，多出来的帧会被拒收——所以顺序类用例给足队列，
     * 只有"超限拒收"那一条故意设小。
     */
    private void newDispatcher(int poolSize, int queuePerSession) {
        AssistantProperties properties = new AssistantProperties();
        properties.getWs().setInboundPoolSize(poolSize);
        properties.getWs().setInboundQueuePerSession(queuePerSession);
        dispatcher = new WsInboundDispatcher(properties);
        dispatcher.init();
    }

    /** 性质 1：dispatch 不等待任务执行完（容器读线程毫秒级返回） */
    @Test
    void dispatch_slowTask_returnsImmediately() throws Exception {
        newDispatcher(2, 20);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        long start = System.currentTimeMillis();

        assertTrue(dispatcher.dispatch("s1", () -> {
            awaitRelease(release);
            finished.set(true);
        }));

        assertTrue(System.currentTimeMillis() - start < 1000, "dispatch 不应阻塞等待任务完成");
        assertFalse(finished.get(), "任务应已交给工作线程，尚未跑完");
        release.countDown();
    }

    /** 性质 2：同会话按到达顺序串行执行，前帧未完时后帧只能排队，不得插队 */
    @Test
    void dispatch_sameSession_executesInOrderAndNeverConcurrently() throws Exception {
        newDispatcher(2, 20);
        CountDownLatch blockFirst = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        AtomicInteger running = new AtomicInteger(0);
        AtomicInteger maxRunning = new AtomicInteger(0);

        assertTrue(dispatcher.dispatch("s1", () -> {
            markRunning(running, maxRunning);
            awaitRelease(blockFirst);
            order.add(1);
            running.decrementAndGet();
        }));
        assertTrue(dispatcher.dispatch("s1", () -> {
            markRunning(running, maxRunning);
            order.add(2);
            running.decrementAndGet();
        }));
        assertTrue(dispatcher.dispatch("s1", () -> {
            markRunning(running, maxRunning);
            order.add(3);
            running.decrementAndGet();
        }));

        awaitUntil(() -> maxRunning.get() == 1);
        assertTrue(order.isEmpty(), "第一帧阻塞期间，后续帧不得开始执行");
        blockFirst.countDown();
        awaitUntil(() -> order.size() == 3);
        assertEquals(List.of(1, 2, 3), order);
        assertEquals(1, maxRunning.get(), "同一会话不得并发处理两帧");
    }

    /** 性质 3：两个会话可并行推进（两任务在 barrier 上会合；若退化为全局串行则等不到） */
    @Test
    void dispatch_differentSessions_runInParallel() throws Exception {
        newDispatcher(2, 20);
        CyclicBarrier bothStarted = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);

        assertTrue(dispatcher.dispatch("s1", () -> {
            awaitBarrier(bothStarted);
            done.countDown();
        }));
        assertTrue(dispatcher.dispatch("s2", () -> {
            awaitBarrier(bothStarted);
            done.countDown();
        }));

        assertTrue(done.await(10, TimeUnit.SECONDS), "不同会话应并行处理，而不是互相等待");
    }

    /** 单会话排队超上限时明确拒收（返回 false 供调用方回错误帧，不静默丢消息） */
    @Test
    void dispatch_whenSessionQueueFull_rejectsFrame() throws Exception {
        newDispatcher(2, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstRunning = new CountDownLatch(1);
        assertTrue(dispatcher.dispatch("s1", () -> {
            firstRunning.countDown();
            awaitRelease(release);
        }));
        // 等第一帧真的被取走执行，后面几帧的排队数才是确定的
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.dispatch("s1", () -> awaitRelease(release)), "排队 1");
        assertTrue(dispatcher.dispatch("s1", () -> awaitRelease(release)), "排队 2（= 上限）");
        assertFalse(dispatcher.dispatch("s1", () -> awaitRelease(release)), "队列已满应拒收并告知调用方");
        release.countDown();
    }

    /** 单帧抛异常不销毁通道：同会话后续帧仍按序执行 */
    @Test
    void dispatch_taskThrows_nextTaskStillRuns() throws Exception {
        newDispatcher(2, 20);
        AtomicInteger ran = new AtomicInteger(0);

        assertTrue(dispatcher.dispatch("s1", () -> {
            throw new IllegalStateException("boom");
        }));
        assertTrue(dispatcher.dispatch("s1", ran::incrementAndGet));

        awaitUntil(() -> ran.get() == 1);
    }

    /** 通道排空后可复用：同一会话再来帧仍能受理（通道映射被回收后重建） */
    @Test
    void dispatch_afterLaneDrained_laneIsReusable() throws Exception {
        newDispatcher(2, 20);
        AtomicInteger ran = new AtomicInteger(0);

        assertTrue(dispatcher.dispatch("s1", ran::incrementAndGet));
        awaitUntil(() -> ran.get() == 1);
        assertTrue(dispatcher.dispatch("s1", ran::incrementAndGet));
        awaitUntil(() -> ran.get() == 2);
    }

    private void markRunning(AtomicInteger running, AtomicInteger maxRunning) {
        maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
    }

    private void awaitRelease(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("两会话未能并行会合", e);
        }
    }

    /** 轮询等待条件成立（最多 5 秒），避免固定 sleep 造成不稳定 */
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时");
    }
}
