package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngRef;

import java.util.List;

/**
 * 课程创建/表单保存与操作事务（03-接口文档 2.6 创建与表单、2.9 课程操作 CS-09）。
 *
 * <p>与 {@link CourseService}（只读查询）分离：本接口全部为写路径或操作事务，
 * 状态机/校验口径对齐 CoursewareOpServiceImpl（下架仅 2→3、上架仅 3→2、隐藏为独立双标记）。
 */
public interface CourseOpService {

    /** 创建课程（CS-08 点"下一步"时）：插入 status=0 未发布记录并生成平台课程编号，返回新 id。 */
    KngCoursewareDtos.IdStatusResponse create(KngCourseDtos.CreateRequest request, String userId);

    /** 保存第一步基本信息表单（CS-07，编辑/创建共用；attachments 仅契约接收不持久化）。 */
    void saveBasic(String id, KngCourseDtos.BasicSaveRequest request);

    /** 隐藏/取消隐藏（CS-09）：独立于 status 的双标记，任意状态可切换。 */
    void hide(String id, Boolean hidden);

    /** 下架单课程：仅已发布(2)可下架，状态置 3 并清上架位（口径同课件 takeDown）。 */
    void takeDown(String id);

    /** 批量上架：仅已下架(3)课程可重新上架，存在非法状态时整体失败。 */
    KngCoursewareDtos.CountResponse batchPutOn(KngCoursewareDtos.IdsRequest request);

    /** 批量下架：仅已发布(2)课程可下架，存在非法状态时整体失败。 */
    KngCoursewareDtos.CountResponse batchTakeDown(KngCoursewareDtos.IdsRequest request);

    /** 批量移动到目标分类（更新 catalog_id + catalog_name 冗余）。 */
    KngCoursewareDtos.CountResponse move(KngCoursewareDtos.MoveRequest request);

    /** 复制课程（CS-09）：深拷贝课程+章节+任务三表，标题"-副本"去重、状态重置未发布、ref 行重建。 */
    KngCoursewareDtos.IdResponse copy(String id, KngCoursewareDtos.CopyRequest request, String userId);

    /** 删除单课程：引用检查（ref_type=2，当前恒无）→ 级联删课程/章节/任务并清理 ref_type=1 引用行。 */
    void delete(String id);

    /** 批量删除：逐个复用 {@link #delete}（每个 id 均执行引用检查）。 */
    void batchDelete(KngCoursewareDtos.IdsRequest request);

    /** 课程引用记录（CS-09 占位）：查 kng_ref（ref_id=课程id 且 ref_type=2），当前恒空。 */
    List<KngRef> references(String id);
}
