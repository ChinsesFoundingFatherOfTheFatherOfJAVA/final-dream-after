package jack.digityhuman.onlineclassroom.service;

/**
 * 在线课堂文档源文件文本抽取（AI 素材源接入，2026-10-28）
 * <p>
 * 只签"把已落库的 kng-files 相对路径读成纯文本"这一件事，供 AI 设计助手 / AI 出题跨模块取素材正文。
 * 实现负责路径越界校验、类型白名单、大小与字符上限、损坏文件异常翻译；业务侧只管传入抽取上限。
 */
public interface KngDocumentTextExtractor {

    /**
     * 抽取文本。
     *
     * @param storagePath 课件源文件在库里的存储路径（形如 {@code /kng-files/202610/xxx.pptx}）
     * @param maxChars    本次用途允许的最大字符数（designAssistant 3 万 / question 2 万）
     * @return 抽取结果（纯文本 + 是否因上限被截断）
     */
    Extracted extract(String storagePath, int maxChars);

    /**
     * 抽取结果。
     *
     * @param text      提取到的纯文本（可能为空串，如扫描件 PDF 无文本层）
     * @param truncated 是否因超出 maxChars 被截断
     */
    record Extracted(String text, boolean truncated) {
    }
}
