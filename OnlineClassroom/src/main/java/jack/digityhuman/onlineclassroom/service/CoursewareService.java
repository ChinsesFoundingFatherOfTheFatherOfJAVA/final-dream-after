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
}
