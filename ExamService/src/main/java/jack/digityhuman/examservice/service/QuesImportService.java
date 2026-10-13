package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.resp.QuesRespDtos;
import org.springframework.web.multipart.MultipartFile;

/**
 * 试题导入（FP-12，03 §6.1/6.2）。
 *
 * <p>模板与导出同构（Excel 5 Sheet 按题型 / Word 按题型分节），由后端就地生成，
 * 保证「下载的模板」与「导入的解析器」永远一致，不会出现模板漂移。
 */
public interface QuesImportService {

    /** 6.1 下载导入模板（excel|word）。 */
    byte[] template(String format);

    /** 6.2 导入：逐行 try-catch，新考核点自动入库。 */
    QuesRespDtos.ImportResult importFile(MultipartFile file, String libId);
}