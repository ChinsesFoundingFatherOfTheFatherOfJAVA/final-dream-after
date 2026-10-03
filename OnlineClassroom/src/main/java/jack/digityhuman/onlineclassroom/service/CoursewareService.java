package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareAttachment;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;

import java.util.List;

public interface CoursewareService {
    KngCoursewareDtos.ListResponse list(
            int pageNo, int pageSize, String orderBy, String orderType,
            KngCoursewareDtos.ListQuery query);

    KngCoursewareDtos.Detail detail(String id);

    KngCoursewareDtos.IdStatusResponse create(KngCoursewareDtos.SaveRequest request, String userId);

    KngCoursewareDtos.IdResponse update(
            String id, KngCoursewareDtos.SaveRequest request, String userId);

    KngCoursewareDtos.StudyRecordResponse studyRecords(
            String coursewareId, int pageNo, int pageSize,
            KngCoursewareDtos.StudyRecordQuery query);

    KngCoursewareDtos.StudyOverview studyOverview(String coursewareId);

    KngCoursewareDtos.FinishTimeOverview finishTimeOverview(String coursewareId);

    List<KngCoursewareDtos.DeptBrief> deptFirstLevel();

    List<KngCoursewareDtos.DeptFinish> deptFinishOverview(
            String coursewareId, KngCoursewareDtos.DeptFinishRequest request);

    KngCoursewareDtos.TranscodeResponse transcodeList(
            int pageNo, int pageSize, KngCoursewareDtos.TranscodeQuery query);

    KngCoursewareDtos.SubtitleResponse subtitleList(KngCoursewareDtos.SubtitleQuery query);

    KngCourseware require(String id);

    List<KngCoursewareFile> files(String coursewareId);

    List<KngCoursewareAttachment> attachments(String coursewareId);

    List<String> descendantCatalogIds(String catalogId);

    /**
     * 课件学分展示值（studyScore 有值直接返回，否则按课程设置"跟随策略"折算）。
     * 供课程详情聚合任务学分之和复用，避免策略逻辑在两个 Service 间重复。
     */
    java.math.BigDecimal studyScoreCalc(KngCourseware courseware);
}
