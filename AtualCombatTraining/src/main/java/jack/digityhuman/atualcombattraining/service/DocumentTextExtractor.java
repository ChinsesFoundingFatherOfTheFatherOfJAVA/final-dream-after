package jack.digityhuman.atualcombattraining.service;

/**
 * 材料文档文本提取（FP-42）
 * <p>
 * 实现为本地 Apache Tika（2026-09-18 用户确认的方案）：不依赖 Dify、无 LLM 费用、离线可用，
 * 覆盖白名单类型 ppt/pptx/doc/docx/pdf/txt/md。
 * 抽取失败（文件损坏、加密、扫描件无文本层等）抛业务异常，由材料服务翻译为"解析失败"事件。
 */
public interface DocumentTextExtractor {

    /**
     * 提取文本。
     *
     * @param storedFilePath 已落盘文件的绝对路径（act-files/ 下）
     * @return 提取到的纯文本（可能为空串，例如扫描件 PDF 无文本层）
     */
    String extract(String storedFilePath);
}