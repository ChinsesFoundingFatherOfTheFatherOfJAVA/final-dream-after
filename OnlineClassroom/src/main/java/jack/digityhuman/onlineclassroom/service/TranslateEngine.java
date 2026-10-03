package jack.digityhuman.onlineclassroom.service;

import java.util.List;

/**
 * 翻译引擎抽象（MT-03 附录B：纯 qwen 翻译，OpenCC 拍板不做；未来换引擎同接口替换）。
 */
public interface TranslateEngine {

    /**
     * 批量翻译一批字幕文本（调用方保证 50 条/批）。
     *
     * @param texts      原文列表（简中）
     * @param targetLang 目标语言码（ha/en/vi）
     * @param langLabel  目标语言展示名（如 "繁體中文"，供 prompt 指定目标语言）
     * @return 译文列表（与输入等长同序；时间戳/序号由调用方本地回填，不信任模型复制）
     */
    List<String> translateBatch(List<String> texts, String targetLang, String langLabel);
}
