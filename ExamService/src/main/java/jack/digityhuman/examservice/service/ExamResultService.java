package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.resp.ExamResultRespDtos;

/** 答卷详情服务（04 §7 API-39/40）。 */
public interface ExamResultService {

    ExamResultRespDtos.ResultView view(String ueId);

    /** 导出答卷 PDF：文件名 = 考生姓名_试卷名_答卷.pdf。 */
    PdfFile exportPdf(String ueId);

    /** PDF 字节流 + 文件名。 */
    record PdfFile(byte[] bytes, String filename) {
    }
}