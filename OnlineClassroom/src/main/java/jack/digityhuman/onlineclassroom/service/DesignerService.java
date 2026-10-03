package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;

/**
 * 课程设计器结构整体读写（03-接口文档 2.7 CS-07a）。
 *
 * <p>设计器为整体 JSON 提交（保存/暂存共用一个端点），落库采用"全删全插"：
 * 事务内删 chapters+tasks 再整体插入，kng_ref 同步重建，天然一致（02-技术方案 §5）。
 */
public interface DesignerService {

    /** 读取设计器结构：章节/任务按 order_index 排序，任务 join 课件实时值（学分/时长/状态/类型）。 */
    KngCourseDtos.DesignerView view(String courseId);

    /** 保存设计器（保存/暂存共用）：校验→更新课程结构字段→全删全插→ref 重建→按 publish 推进状态。 */
    void save(String courseId, KngCourseDtos.DesignerSaveRequest request);
}
