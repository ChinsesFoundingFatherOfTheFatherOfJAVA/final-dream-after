package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeRespDtos;

/** 考试安排服务（04 §4：/api/exam/arrange）。 */
public interface ArrangeService {

    PageResult<ArrangeRespDtos.ArrangeItem> list(int current, int limit, String orderby,
                                                 String direction, ArrangeDtos.ListQuery query);

    ArrangeRespDtos.ArrangeDetail detail(String id);

    String create(ArrangeDtos.SaveReq req);

    void update(String id, ArrangeDtos.SaveReq req);

    /** 发布：校验试卷已发布 → 生成不可变快照 → 记 exam_paper_use → 改状态。 */
    void publish(String id);

    void end(String id);

    String copy(String id);

    void delete(String id);

    void batch(ArrangeDtos.BatchReq req);

    ArrangeRespDtos.LinkResp link(String id);
}