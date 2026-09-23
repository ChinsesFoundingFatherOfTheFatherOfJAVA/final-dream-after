package jack.digityhuman.atualcombattraining.websocket;

import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * WS 入站帧派发器：把业务处理从容器读线程上摘下来
 * <p>
 * 为什么必须有（2026-09-21 修复"WS 经常连接超时"）：聊天主链路一路阻塞到 Dify 流结束
 * （{@code dify.agent-timeout-ms} 实测单次可达数百秒），而 Tomcat 对一条 WS 连接的入站帧
 * 是由该连接的读线程逐个同步派发给 {@code handleTextMessage} 的——读线程被占住期间，
 * 客户端按时回的心跳应答根本没人读，{@code ATTR_LAST_PONG} 停在旧值，
 * 于是心跳巡检在 pong-timeout（80s）时把一条完全健康的连接踢掉，
 * 本轮后续帧再写就报 {@code java.io.IOException: closed}。
 * <p>
 * 做法：每个 sessionId 一条"通道"（Lane），同一会话的帧严格按到达顺序串行执行
 * （轮次上下文 {@link AssistantRoundRegistry} 与工作区写入都按串行假设写的，不能并发化）；
 * 不同会话之间并行，并行度即 {@code assistant.ws.inbound-pool-size}。
 * 读线程上只留 pong 处理与入队，毫秒级返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsInboundDispatcher {

    /** 线程池队列容量：每会话同一时刻最多一个排空任务在排队，取会话数级别的余量即可 */
    private static final int EXECUTOR_QUEUE_CAPACITY = 256;

    private final AssistantProperties properties;

    private ThreadPoolTaskExecutor executor;

    /** sessionId → 该会话的串行通道 */
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    /** 单会话串行通道：待执行帧队列 + 是否已有排空任务在跑 */
    private static final class Lane {
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private boolean draining;
    }

    @PostConstruct
    public void init() {
        int poolSize = Math.max(1, properties.getWs().getInboundPoolSize());
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(poolSize);
        taskExecutor.setMaxPoolSize(poolSize);
        taskExecutor.setQueueCapacity(EXECUTOR_QUEUE_CAPACITY);
        taskExecutor.setThreadNamePrefix("assistant-ws-inbound-");
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(10);
        taskExecutor.initialize();
        executor = taskExecutor;
        log.info("WS 入站派发线程池已启动：工作线程 {}，单会话排队上限 {}",
                poolSize, properties.getWs().getInboundQueuePerSession());
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * 把一个入站帧交给该会话的串行通道执行（调用方须已捕获 payload，任务内不得依赖读线程上下文）。
     *
     * @param sessionId 会话 ID（通道键）
     * @param task 业务处理任务
     * @return true=已受理；false=未受理（该会话排队已满或线程池饱和），调用方需自行给用户反馈
     */
    public boolean dispatch(String sessionId, Runnable task) {
        Lane lane = lanes.computeIfAbsent(sessionId, key -> new Lane());
        Runnable drain = null;
        int limit = Math.max(1, properties.getWs().getInboundQueuePerSession());
        synchronized (lane) {
            // 上限只约束"排队等待"的帧（正在执行的那一帧已出队）；超限说明这一轮迟迟不结束，
            // 与其无上限堆内存，不如明确拒收让调用方给用户反馈
            if (lane.pending.size() >= limit) {
                log.warn("WS 入站队列已满，本帧拒收 sessionId={} 排队中={}", sessionId, lane.pending.size());
                return false;
            }
            lane.pending.addLast(task);
            if (!lane.draining) {
                lane.draining = true;
                drain = () -> drainLane(sessionId, lane);
            }
        }
        if (drain == null) {
            return true;
        }
        try {
            executor.execute(drain);
            return true;
        } catch (RejectedExecutionException e) {
            int dropped;
            synchronized (lane) {
                dropped = lane.pending.size();
                lane.pending.clear();
                lane.draining = false;
            }
            lanes.remove(sessionId, lane);
            log.error("WS 入站线程池饱和，本会话待处理帧已丢弃 sessionId={} 数量={}", sessionId, dropped, e);
            return false;
        }
    }

    /**
     * 串行跑空该会话队列：一次只处理一帧，处理完再取下一帧。
     * 队列跑空即释放通道并摘除映射（映射只保留"正在使用"的会话，不会无限增长）。
     */
    private void drainLane(String sessionId, Lane lane) {
        while (true) {
            Runnable next;
            synchronized (lane) {
                next = lane.pending.pollFirst();
                if (next == null) {
                    lane.draining = false;
                    lanes.remove(sessionId, lane);
                    return;
                }
            }
            try {
                next.run();
            } catch (Exception e) {
                // 单帧异常不终止通道：后续帧仍按顺序执行
                log.error("WS 入站帧执行异常 sessionId={}", sessionId, e);
            }
        }
    }
}
