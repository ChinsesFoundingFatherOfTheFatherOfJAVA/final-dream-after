package jack.digityhuman.onlineclassroom.service;

import java.nio.file.Path;
import java.util.List;

/**
 * ASR 引擎抽象（MT-03 附录B 决策：接口隔离，未来 FunASR 本地实现同接口替换，内网约束当前允许出网）。
 */
public interface AsrEngine {

    /**
     * 转写音频为句级文本（毫秒时间戳）。
     *
     * @param wav          16k 单声道 wav 文件（转码产物 /kng-files/audio/{coursewareId}.wav）
     * @param coursewareId 课件 id（云端实现用于临时对象命名与清理）
     * @return 句子列表（beginMs/endMs/text，按时间升序）
     */
    List<SrtService.Sentence> transcribe(Path wav, String coursewareId);
}
