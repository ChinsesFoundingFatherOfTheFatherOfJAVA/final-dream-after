package jack.digityhuman.speechtraining.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 声音角色映射配置（v1.1）
 * 配置项 voice.map.{角色名}={千问AI平台voice值}，如 voice.map.温柔女声=longwan_v2
 * 用于管理端"声音角色"下拉名与库中 voice_id 不一致时的兜底转换
 */
@Data
@Component
@ConfigurationProperties(prefix = "voice")
public class VoiceMapProperties {

    private Map<String, String> map = new HashMap<>();

    /**
     * 解析声音：命中映射返回千问AI平台 voice 值，未命中按原值透传（库中直接存千问AI平台 voice）
     */
    public String resolve(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return null;
        }
        return map.getOrDefault(voiceId, voiceId);
    }
}
