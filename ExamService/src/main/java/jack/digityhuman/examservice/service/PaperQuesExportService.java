package jack.digityhuman.examservice.service;

/** 试卷试题列表导出（只读考试编辑页）。 */
public interface PaperQuesExportService {

    /**
     * 按与只读列表相同的筛选条件导出全部匹配试题。
     *
     * @param paperId 试卷 ID
     * @param type 题型，-1 表示全部
     * @param levelType 难度，-1 表示全部
     * @param keyword 试题名称关键词
     * @return xlsx 文件字节流
     */
    byte[] exportQuestions(String paperId, int type, int levelType, String keyword);
}
