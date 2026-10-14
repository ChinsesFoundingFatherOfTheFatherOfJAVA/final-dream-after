package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.PaperQuesDtos;
import jack.digityhuman.examservice.dto.resp.PaperQuesRespDtos;

import java.util.Map;

/** 固定卷题目服务（FP-10/13，07 §4）：试卷与题库试题「同源引用」，关联表只存分值/分组/排序。 */
public interface PaperQuesService {

    /** 4.2 编辑页画布（按大题分组；已发布附可编辑字段白名单）。 */
    PaperQuesRespDtos.EditCanvas editCanvas(String paperId);

    /** 4.1 管理页试题列表（跨库聚合，带所属题库名）。 */
    PageResult<PaperQuesRespDtos.PaperQuesItem> questions(String paperId, int current, int limit,
                                                          int type, int levelType, String keyword);

    /** 4.3 添加题目：题目内容进题库（同源写入），卷子只建关联。 */
    Map<String, Object> addQuestion(String paperId, PaperQuesDtos.AddReq req);

    /** 4.4 从题库选题：只建关联。 */
    int pick(String paperId, PaperQuesDtos.PickReq req);

    /** 4.5 编辑（未发布可改全部；已发布仅内容白名单）。 */
    void updateQuestion(String paperId, String quesId, PaperQuesDtos.UpdateReq req);

    /** 4.6 移除关联（不删题库试题）。 */
    void removeQuestion(String paperId, String quesId);

    /** 4.6 批量移除关联。 */
    int removeQuestions(String paperId, PaperQuesDtos.BatchDeleteReq req);

    /** 4.7 拖拽排序（含跨大题）。 */
    void sort(String paperId, PaperQuesDtos.SortReq req);

    /** 4.8 快速设置全部分数。 */
    void batchScore(String paperId, PaperQuesDtos.BatchScoreReq req);

    /** 5 概览统计（总数 + 按题型/考核点/难度）。 */
    PaperQuesRespDtos.Overview overview(String paperId);

    /** 冗余计数维护（总分/试题数），增删改后调用。 */
    void refreshCounters(String paperId);
}