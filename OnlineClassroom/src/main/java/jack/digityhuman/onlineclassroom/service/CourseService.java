package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;

import java.util.List;

/**
 * 课程查询/详情/章节统计/学习数据服务（03-接口文档 2.1~2.5）。
 *
 * <p>创建/编辑/操作类端点（创建表单、上架下架隐藏移动复制删除、设计器结构读写）由 CourseOp/CourseDesigner 侧实现。
 */
public interface CourseService {

    /** 课程列表（CS-01）：分页/多条件筛选，目录子树展开复用课件模块实现。 */
    KngCourseDtos.ListResponse list(
            int pageNo, int pageSize, String orderBy, String orderType,
            KngCourseDtos.ListQuery query);

    /** 课程详情（CS-03）：基础字段 + 任务聚合（标准时长/学分） + 贡献者补齐。 */
    KngCourseDtos.Detail detail(String id);

    /** 章节学习统计（CS-04）：章节分组 + 每任务课件近 7 天/总学习数；无章节课程返回 chapterId=null 分组。 */
    List<KngCourseDtos.ChapterStudyStats> chapterStudyStats(String courseId);

    /** 学习概览三项计数（CS-06），以本课程任务的课件 id 集合为过滤口径。 */
    KngCourseDtos.StudyOverview studyOverview(String courseId);

    /** 完课用时分布五档（CS-06），以本课程任务的课件 id 集合为过滤口径。 */
    KngCourseDtos.FinishTimeOverview finishTimeOverview(String courseId);

    /** 学习记录筛选用的一级部门下拉（与课件模块同实现）。 */
    List<KngCourseDtos.DeptBrief> deptFirstLevel();

    /** 部门完成度统计（CS-06），以本课程任务的课件 id 集合为过滤口径。 */
    List<KngCourseDtos.DeptFinish> deptFinishOverview(
            String courseId, KngCourseDtos.DeptFinishRequest request);

    /** 课程内课件学习记录（CS-05）：完全复用课件 study-records 逻辑，响应行多 studySchedule 字段。 */
    KngCoursewareDtos.StudyRecordResponse studyRecords(
            String courseId, String coursewareId, int pageNo, int pageSize,
            KngCoursewareDtos.StudyRecordQuery query);
}
