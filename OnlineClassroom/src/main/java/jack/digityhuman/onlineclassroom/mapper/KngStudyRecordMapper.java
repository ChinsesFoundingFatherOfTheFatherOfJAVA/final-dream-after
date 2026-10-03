package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.entity.KngStudyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KngStudyRecordMapper extends BaseMapper<KngStudyRecord> {

    long countRecordQuery(
            @Param("coursewareId") String coursewareId,
            @Param("query") KngCoursewareDtos.StudyRecordQuery query,
            @Param("deptIds") List<String> deptIds,
            @Param("userIds") List<String> userIds);

    List<KngStudyRecord> selectRecordPage(
            @Param("coursewareId") String coursewareId,
            @Param("query") KngCoursewareDtos.StudyRecordQuery query,
            @Param("deptIds") List<String> deptIds,
            @Param("userIds") List<String> userIds,
            @Param("offset") int offset,
            @Param("limit") int limit);

    List<KngReadDtos.StudyCountRow> countByCoursewareIds(@Param("coursewareIds") List<String> coursewareIds);

    KngReadDtos.StudyOverviewRow selectOverview(
            @Param("coursewareId") String coursewareId,
            @Param("sevenDaysAgo") LocalDateTime sevenDaysAgo);

    KngReadDtos.FinishTimeRow selectFinishTimeOverview(@Param("coursewareId") String coursewareId);

    List<KngReadDtos.StudyDeptCountRow> selectDeptCounts(
            @Param("coursewareId") String coursewareId,
            @Param("deptIds") List<String> deptIds);

    // ==================== 课程维度（以课件 id 集合为过滤口径，kng_study_record 无课程列） ====================

    /** 按课件 id 集合统计近 7 天/总数（课程章节学习统计用）；coursewareIds 为空时由 Service 拦截，不发 SQL。 */
    List<KngReadDtos.StudyRecentCountRow> selectRecentAndTotalByCoursewareIds(
            @Param("coursewareIds") List<String> coursewareIds,
            @Param("sevenDaysAgo") LocalDateTime sevenDaysAgo);

    /** 按课件 id 集合取学习概览三项计数（课程学习数据 tab 用），口径同 selectOverview。 */
    KngReadDtos.StudyOverviewRow selectOverviewByCoursewareIds(
            @Param("coursewareIds") List<String> coursewareIds,
            @Param("sevenDaysAgo") LocalDateTime sevenDaysAgo);

    /** 按课件 id 集合取完课用时五档分布（课程学习数据 tab 用），口径同 selectFinishTimeOverview。 */
    KngReadDtos.FinishTimeRow selectFinishTimeOverviewByCoursewareIds(
            @Param("coursewareIds") List<String> coursewareIds);

    /** 按课件 id 集合取部门学习/完课计数（课程部门完成度用），口径同 selectDeptCounts。 */
    List<KngReadDtos.StudyDeptCountRow> selectDeptCountsByCoursewareIds(
            @Param("coursewareIds") List<String> coursewareIds,
            @Param("deptIds") List<String> deptIds);
}
