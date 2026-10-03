package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.entity.KngRef;

import java.util.List;

/**
 * kng_ref 引用维护门面（CS-10 引用联动）。
 *
 * <p>课程的每个任务引用一个课件，任务写入时同步维护 kng_ref（ref_type=1 课程 → courseware_id）；
 * 设计器保存/课程复制时整体重建，课程删除时清理，课件删除检查与课件"引用记录"读本表。
 * ref_type 口径：1=课程引用课件（本模块启用）、2=培训/考试引用课程（未来模块，当前恒无数据）。
 */
public interface CourseRefService {

    /**
     * 重建课程的任务引用行（事务内：先删 ref_id=courseId 且 ref_type=1 的旧行，再按任务序批量插入）。
     *
     * @param courseId     课程 id（kng_ref.ref_id）
     * @param courseTitle  课程名快照（kng_ref.ref_name，课件删除提示文案用）
     * @param coursewareIds 任务序的课件 id 列表（去重由调用方保证与否均安全，空列表=只删不插）
     */
    void rebuildForCourse(String courseId, String courseTitle, List<String> coursewareIds);

    /** 删除课程的全部任务引用行（课程删除时连带清理，CS-09/CS-10）。 */
    void deleteByCourse(String courseId);

    /** 查询引用某课件的课程引用行（ref_type=1），供课件删除检查与课件侧引用记录展示。 */
    List<KngRef> listCourseRefs(String coursewareId);
}
