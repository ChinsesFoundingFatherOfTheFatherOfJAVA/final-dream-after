package jack.digityhuman.speechtraining.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTS 缓存命中单测（v1.1，FP-28 验收标准）：
 * 相同"文本+声音+语速"第二次请求直接返回缓存 URL，不重复合成。
 */
@ExtendWith(MockitoExtension.class)
class TtsCacheHitTest {

    @TempDir
    Path tempDir;

    @Mock
    private VoiceMapProperties voiceMapProperties;

    @Mock
    private jack.digityhuman.speechtraining.doubao.DoubaoSaucClient doubaoSaucClient;

    /** 使用真实文件系统的缓存服务（临时目录） */
    private TtsCacheService ttsCacheService;
    private SpeechService speechService;
    private final AtomicInteger synthesizeCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        // 用临时目录实例化 FileStorageService（反射注入目录字段，@Value 在纯单测中不生效）
        jack.digityhuman.speechtraining.common.FileStorageService fileStorageService =
                new jack.digityhuman.speechtraining.common.FileStorageService();
        setField(fileStorageService, "coverImageDir", tempDir.resolve("coverImage").toString());
        setField(fileStorageService, "avatarDir", tempDir.resolve("avatar").toString());
        setField(fileStorageService, "audioDir", tempDir.resolve("audio").toString());
        setField(fileStorageService, "audioUrlPrefix", "/audio");
        java.lang.reflect.Method init = fileStorageService.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(fileStorageService);

        ttsCacheService = new TtsCacheService(fileStorageService);

        // SpeechService 子类覆写 doSynthesize，统计真实合成次数
        speechService = new SpeechService(ttsCacheService, voiceMapProperties, doubaoSaucClient) {
            @Override
            byte[] doSynthesize(String text, String voice, int speed) {
                synthesizeCount.incrementAndGet();
                return oneSecondOfMp3();
            }
        };
    }

    /**
     * 同键并发只合成一次：开局时 N 个学员会对同一句陪练者问题同时发 TTS，
     * 不去重就是 N 次百炼调用（计时器用于撑开合成窗口，让 5 个线程确定落在同一次合成上）。
     */
    @Test
    void 同键并发只合成一次() throws Exception {
        int callers = 5;
        SpeechService slow = new SpeechService(ttsCacheService, voiceMapProperties, doubaoSaucClient) {
            @Override
            byte[] doSynthesize(String text, String voice, int speed) {
                synthesizeCount.incrementAndGet();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return oneSecondOfMp3();
            }
        };

        java.util.concurrent.CyclicBarrier ready = new java.util.concurrent.CyclicBarrier(callers);
        java.util.List<java.util.concurrent.Callable<SpeechService.TtsResult>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> {
                ready.await();
                return slow.tts("今天吃了吗", "longwan_v2", 30);
            });
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            java.util.List<java.util.concurrent.Future<SpeechService.TtsResult>> results = pool.invokeAll(tasks);
            String url = results.get(0).get().url();
            for (java.util.concurrent.Future<SpeechService.TtsResult> r : results) {
                assertEquals(url, r.get().url(), "同键并发应返回同一个缓存文件");
            }
            assertEquals(1, synthesizeCount.get(), "同键并发只允许一次真实合成");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 约 1 秒的合法 MPEG1 Layer III 帧流（128kbps/44100Hz，每帧 417 字节、1152 采样）。
     * 时长现在按帧头实测，全零字节冒充 MP3 只会解析出 0ms。
     */
    private static byte[] oneSecondOfMp3() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 39; i++) {
            out.write(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00}, 0, 4);
            out.write(new byte[413], 0, 413);
        }
        return out.toByteArray();
    }

    /** 反射注入 @Value 字段 */
    private static void setField(Object target, String name, String value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void 相同请求_第二次命中缓存不重复合成() {
        // 第一次：未命中缓存，真实合成
        SpeechService.TtsResult first = speechService.tts("今天吃了吗", "longwan_v2", 30);
        assertFalse(first.cached());
        assertTrue(first.url().startsWith("/audio/tts/"));
        assertTrue(first.url().endsWith(".mp3"));
        assertEquals(1, synthesizeCount.get());
        assertEquals(1019, first.durationMs());

        // 第二次相同"文本+声音+语速"：命中缓存，不再合成
        SpeechService.TtsResult second = speechService.tts("今天吃了吗", "longwan_v2", 30);
        assertTrue(second.cached());
        assertEquals(first.url(), second.url());
        assertEquals(1, synthesizeCount.get(), "相同请求不应重复合成");

        // 缓存键确定性：相同输入产出相同文件名
        assertEquals(ttsCacheService.cacheKey("今天吃了吗", "longwan_v2", 30),
                ttsCacheService.cacheKey("今天吃了吗", "longwan_v2", 30));
    }

    @Test
    void 不同语速或文本_不命中同一缓存() {
        speechService.tts("今天吃了吗", "longwan_v2", 30);
        speechService.tts("今天吃了吗", "longwan_v2", 50); // 语速不同
        speechService.tts("那咱们聊聊产品吧", "longwan_v2", 30); // 文本不同
        assertEquals(3, synthesizeCount.get(), "文本或语速不同应分别合成");
    }
}
