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
        speechService = new SpeechService(ttsCacheService, voiceMapProperties) {
            @Override
            byte[] doSynthesize(String text, String voice, int speed) {
                synthesizeCount.incrementAndGet();
                return new byte[32_000]; // 1 秒音频
            }
        };
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
        assertEquals(1000, first.durationMs());

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
