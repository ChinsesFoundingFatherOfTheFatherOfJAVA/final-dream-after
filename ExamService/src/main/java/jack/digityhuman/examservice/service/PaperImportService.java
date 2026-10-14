package jack.digityhuman.examservice.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 快速出卷导入（FP-12，07 §6）。
 *
 * <p>一份 Excel 一次生成一整张固定卷：5 个 Sheet 各对应一个题型，
 * 表头在第 3 行、数据从第 4 行开始；题目内容走题库同源写入。
 */
public interface PaperImportService {

    /** 6.1 下载导入模板（classpath 静态资源，与解析器同源）。 */
    byte[] template();

    /** 6.2 导入：逐行 try-catch，返回 {successCount, failCount, errors, paperId}。 */
    Map<String, Object> importFile(MultipartFile file, String libId, String catalogId, Integer secrecy);

    /**
     * 6.2 导入到已有试卷（固定卷编辑页的「导入」入口）。
     *
     * <p>与新建导入共用解析器，区别只是不新建卷壳、把题目追加到 {@code paperId}。
     */
    Map<String, Object> importFile(MultipartFile file, String libId, String catalogId, Integer secrecy, String paperId);
}
