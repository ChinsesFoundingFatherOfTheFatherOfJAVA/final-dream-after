package jack.digityhuman.speechtraining.service;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 对话素材文本抽取（SG-03）：doc/docx/ppt/pptx/pps/pdf → 纯文本（30k 截断）。
 */
public interface MaterialExtractService {

    /**
     * 抽取上传文件的文本。
     *
     * @throws jack.digityhuman.common.api.BizException 格式不支持/抽取失败
     */
    ExtractResult extract(MultipartFile file);

    /**
     * 抽取磁盘文件文本（在线课堂课件源文件路径用）。
     *
     * @param fileName 原文件名（判定扩展名）
     */
    ExtractResult extract(Path file, String fileName);

    /** 抽取结果：text 已按上限截断，truncated 标记是否截断。 */
    record ExtractResult(String text, boolean truncated, int chars) {
    }
}
