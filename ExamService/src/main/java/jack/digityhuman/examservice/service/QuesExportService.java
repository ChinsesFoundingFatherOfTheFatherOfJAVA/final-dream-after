package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.QuesDtos;

/** 试题导出（FP-13，03 §6.3）：按当前筛选条件导出 Excel / Word。 */
public interface QuesExportService {

    /** Excel：5 个 Sheet 按题型分（单选/多选/判断/填空/问答）。 */
    byte[] exportExcel(QuesDtos.ListQuery query);

    /** Word：按题型分节，段落式（序号、题干 → A、选项 → 【正确答案】…）。 */
    byte[] exportWord(QuesDtos.ListQuery query);
}