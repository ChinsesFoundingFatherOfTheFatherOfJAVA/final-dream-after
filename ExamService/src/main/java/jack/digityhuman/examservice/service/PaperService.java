package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.PaperDtos;
import jack.digityhuman.examservice.dto.resp.PaperRespDtos;

/** 试卷服务（FP-05/06，07 §2）。 */
public interface PaperService {

    PageResult<PaperRespDtos.PaperItem> list(int current, int limit, String orderby,
                                             String direction, PaperDtos.ListQuery query);

    PaperRespDtos.PaperDetail detail(String id);

    String create(PaperDtos.SaveReq req);

    void update(String id, PaperDtos.SaveReq req);

    void disable(String id, int disabled);

    void delete(String id);

    void transfer(String id, String toUserId);

    void changeCatalog(String id, String catalogId);

    /** 2.4 切换抽题方式（0题型+难度 1题型 2难度 3数量）。 */
    void updateQuesMode(String id, int quesMode);

    /** 试卷列表导出 Excel（按当前筛选条件）。 */
    byte[] exportExcel(PaperDtos.ListQuery query);

    /** 2.6 发布（校验链：先有题目/策略 → 再有分值）。 */
    void release(String id);

    /** 2.5 共享范围读取（仅已发布可共享）。 */
    jack.digityhuman.examservice.dto.resp.ExamLibRespDtos.ShareInfo getShare(String id);

    /** 2.5 共享范围保存（全量覆盖；未发布 40030）。 */
    void saveShare(String id, jack.digityhuman.examservice.dto.req.ExamLibDtos.ShareReq req);

    /** 2.7 引用列表。 */
    java.util.List<PaperRespDtos.PaperUseItem> useList(String id);

    /** 2.4 批量操作：删除 / 转移 / 修改分类。 */
    void batch(PaperDtos.BatchReq req);
}
