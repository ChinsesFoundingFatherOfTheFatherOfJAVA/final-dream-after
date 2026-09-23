package jack.digityhuman.speechtraining.doubao;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 豆包流式语音识别（SAUC）接入配置。
 * <p>endpoint 语义（官方文档）：
 * <ul>
 *   <li>bigmodel_async 双向流式优化版：唯一支持 enable-nonstream（二遍识别）的链路，实测整段灌包 7s 音频约 2s 返回；</li>
 *   <li>bigmodel_nostream 流式输入模式：准确率更高、支持 audio.language，但长音频更慢（58s 实测 11s）；</li>
 *   <li>bigmodel 双向流式旧版：每入一包出一包，不推荐。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "speech.asr.doubao")
public class DoubaoAsrProperties {

    /** 完整 wss 地址，末段即 endpoint */
    private String url = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
    /** 控制台 APP ID → 请求头 X-Api-App-Key */
    private String appKey;
    /** 控制台 Access Token → 请求头 X-Api-Access-Key */
    private String accessKey;
    /** 资源 ID：1.0 为 volc.bigasr.sauc.duration，2.0 为 volc.seedasr.sauc.duration */
    private String resourceId = "volc.seedasr.sauc.duration";
    /** request.model_name，官方目前仅 bigmodel */
    private String modelName = "bigmodel";
    /** 二遍识别：VAD 分句后用非流式模型重识别该句，仅 bigmodel_async 支持 */
    private boolean enableNonstream = true;
    /** 语种检测：响应 additions.lid_lang 返回识别到的语种标签（粤语为 speech_dia_cant） */
    private boolean enableLid = true;
    /** 单包音频时长（ms），官方建议 100~200 */
    private int chunkMs = 200;
    /** 发包间隔（ms）；整段上传场景实测 0 即可，出现服务端等包异常时再调大 */
    private int chunkIntervalMs = 0;
    /**
     * 末包等待超时（秒）——音频是整段快传（chunkIntervalMs=0），本值只覆盖"发完最后一个包到拿到终结结果"的等待，
     * 实测真人语音 <1s；调小是为了让"录了但没声音"这种服务端不再回包的场景尽早失败，而不是让学员干等。
     */
    private int timeoutSeconds = 10;
}
