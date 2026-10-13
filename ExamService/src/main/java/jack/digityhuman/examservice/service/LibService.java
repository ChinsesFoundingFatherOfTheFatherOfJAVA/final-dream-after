package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ExamLibDtos;
import jack.digityhuman.examservice.dto.resp.ExamLibRespDtos;

/**
 * 题库服务（FP-04）：列表（分类过滤含子分类/我创建的/共享给我的/权限能力集）、新建（自动建默认考点）、
 * 编辑、启停、删除（级联逻辑删）、共享（全量覆盖）、转移、修改分类、批量。
 */
public interface LibService {

    /** 列表（03 §2.1：分页 current/limit/orderby/direction 在 query）。 */
    PageResult<ExamLibRespDtos.LibItem> list(int current, int limit, String orderby,
                                             String direction, ExamLibDtos.ListQuery query);

    /** 详情（03 §2.2）。 */
    ExamLibRespDtos.LibDetail detail(String id);

    /** 新建（03 §2.3；自动建"默认考点"）。 */
    String create(ExamLibDtos.SaveReq req);

    /** 编辑基本信息（03 §2.3 同构）。 */
    void update(String id, ExamLibDtos.SaveReq req);

    /** 启停（03 §2.4）。 */
    void disable(String id, int disabled);

    /** 删除（03 §2.5：仅创建者/超管 40003；级联逻辑删试题/选项/考核点关联）。 */
    void delete(String id);

    /** 共享范围读取（03 §2.6 GET）。 */
    ExamLibRespDtos.ShareInfo getShare(String id);

    /** 共享范围保存（03 §2.6 PUT：全量覆盖）。 */
    void saveShare(String id, ExamLibDtos.ShareReq req);

    /** 转移（03 §2.7：创建人变更）。 */
    void transfer(String id, String toUserId);

    /** 修改分类（03 §2.8）。 */
    void changeCatalog(String id, String catalogId);

    /** 批量（03 §2.9：delete|transfer|changeCatalog）。 */
    void batch(ExamLibDtos.BatchReq req);

    /** 2.10 查重条件位。 */
    ExamLibRespDtos.DuplicateCondition duplicateCondition(String id);
}
