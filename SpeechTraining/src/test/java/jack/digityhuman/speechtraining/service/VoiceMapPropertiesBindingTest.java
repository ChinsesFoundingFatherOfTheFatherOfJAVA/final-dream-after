package jack.digityhuman.speechtraining.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 声音角色映射绑定测试（v1.1）：
 * application.properties 中中文 key 以 \\uXXXX 转义书写（保证文件纯 ASCII、任何编辑器不乱码），
 * 验证 Spring Boot 加载后仍绑定为正确的中文字符串。
 */
@SpringBootTest
class VoiceMapPropertiesBindingTest {

    @Autowired
    private VoiceMapProperties voiceMapProperties;

    @Test
    void 中文key经Unicode转义后正确绑定() {
        assertEquals("longwan_v2", voiceMapProperties.getMap().get("温柔女声"));
        assertEquals("longcheng_v2", voiceMapProperties.getMap().get("沉稳男声"));
        assertEquals("longxiaochun_v2", voiceMapProperties.getMap().get("亲切女声"));
        // 未命中按原值透传
        assertEquals("longcheng_v2", voiceMapProperties.resolve("longcheng_v2"));
    }
}
