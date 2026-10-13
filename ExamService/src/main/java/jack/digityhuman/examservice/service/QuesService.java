package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.QuesDtos;
import jack.digityhuman.examservice.dto.resp.QuesRespDtos;

/** 试题（FP-07/08，03 §3）。 */
public interface QuesService {

    PageResult<QuesRespDtos.QuesItem> list(int current, int limit, String orderby, String direction, QuesDtos.ListQuery query);

    QuesRespDtos.QuesDetail detail(String id);

    String create(QuesDtos.SaveReq req);

    void update(String id, QuesDtos.SaveReq req);

    void delete(String id);

    void batch(QuesDtos.BatchReq req);

    /** 3.8 按题型执行查重，返回被判为重复的试题数。 */
    int checkDuplicate(String libId, int type);
}