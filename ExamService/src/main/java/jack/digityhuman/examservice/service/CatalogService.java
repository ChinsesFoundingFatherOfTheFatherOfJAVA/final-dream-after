package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.ExamCatalogDtos;
import jack.digityhuman.examservice.dto.resp.ExamCatalogRespDtos;

/**
 * 分类树服务（FP-03）：树读取（含管理员合并）、批量新建（缩进文本）、编辑、移动（子树 routing_path 重算）、
 * 禁用/启用、删除（有子分类或挂库阻止）。
 */
public interface CatalogService {

    /** 树（含每节点 libQty/childCatalogQty/管理员列表；权限继承在读取时合并）。 */
    ExamCatalogRespDtos.TreeNode tree();

    /** 分类详情（编辑抽屉：只读信息 + 名称 + 管理员）。 */
    ExamCatalogRespDtos.CatalogDetail detail(String id);

    /** 批量新建（一行一分类、空格缩进表层级；逐行校验返回明细）。 */
    ExamCatalogDtos.BatchCreateResult createBatch(ExamCatalogDtos.BatchCreateReq req);

    /** 编辑（名称/管理员/继承开关）。 */
    void update(String id, ExamCatalogDtos.UpdateReq req);

    /** 移动（目标父校验非自身子孙 + 层级≤10；整棵子树 routing_path 重算）。 */
    void move(String id, String targetParentId);

    /** 禁用/启用。 */
    void disable(String id, int disabled);

    /** 删除（有子分类或有挂载题库 → 40001）。 */
    void delete(String id);
}
