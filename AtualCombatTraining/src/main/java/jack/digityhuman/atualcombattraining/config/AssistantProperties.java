package jack.digityhuman.atualcombattraining.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 设计助手模块配置（assistant.*）
 * <p>
 * 覆盖：材料文件存储目录与大小上限、三个线程池参数（解析 / 生成 / 发布）、WS 心跳与入站线程参数、ASR 模型名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {

    /** 材料文件存储目录（相对进程工作目录） */
    private String fileDir = "./act-files/";
    /** 上传文件大小上限（字节） */
    private long fileMaxSizeBytes = 52_428_800L;

    /** 材料解析线程池（本地 Tika 文本提取，与生成类任务隔离） */
    private Pool parsePool = new Pool(2, 4, 64);
    /** 生成类任务线程池（主题生成 / 案例生成） */
    private Pool generatePool = new Pool(2, 4, 64);
    /** 发布初始化线程池（逐案例编译剧本） */
    private Pool publishPool = new Pool(2, 4, 128);

    private Ws ws = new Ws();
    private Asr asr = new Asr();

    /** 线程池参数 */
    @Data
    public static class Pool {
        private int core = 2;
        private int max = 4;
        private int queue = 64;

        public Pool() {
        }

        public Pool(int core, int max, int queue) {
            this.core = core;
            this.max = max;
            this.queue = queue;
        }
    }

    /** WebSocket 心跳参数 */
    @Data
    public static class Ws {
        /** 服务端 ping 间隔（毫秒） */
        private long pingIntervalMs = 20_000L;
        /** 无 pong 超时（毫秒），超时关闭连接 */
        private long pongTimeoutMs = 80_000L;
        /**
         * 入站业务帧的工作线程数。
         * <p>
         * 业务帧（聊天主链路）会阻塞到 Dify 流结束，必须在容器读线程之外执行，
         * 否则期间收不到 pong 会被心跳误判为死连接；同一会话串行，故该值即"同时处理的会话数"。
         */
        private int inboundPoolSize = 8;
        /** 单会话入站排队上限（不含正在执行的那一帧；超出直接拒收并告警，避免上游卡死时内存无上限增长） */
        private int inboundQueuePerSession = 20;
    }

    /** 语音识别参数 */
    @Data
    public static class Asr {
        /** 百炼 Paraformer 模型名 */
        private String model = "paraformer-v2";
    }
}