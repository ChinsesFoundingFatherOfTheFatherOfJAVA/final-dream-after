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
}
