package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStatisticsResp;

public interface ProjectStatisticsService {
    ProjectStatisticsResp.Summary summary(Long projectId);

    ProjectStatisticsResp.TimesAverages timesAverages(Long projectId);

    ProjectStatisticsResp.Radar radar(Long projectId);

    ProjectStatisticsResp.Active active(Long projectId);

    PageResult<ProjectStatisticsResp.StudentRow> students(
            Long projectId, ProjectStudentQueryReq req);

    PageResult<ProjectStatisticsResp.DeptRow> departments(
            Long projectId, ProjectStudentQueryReq req);

    ProjectStatisticsResp.Tops tops(Long projectId, String userId);

    ProjectStatisticsResp.Tops topsForStudent(Long projectId, String userId);

    ProjectStatisticsResp.PracticeAnalysis practiceAnalysis(
            Long projectId, String userId, String dimension);

    ProjectStatisticsResp.PracticeAnalysis practiceAnalysisForStudent(
            Long projectId, String userId, String dimension);

    PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecords(
            Long projectId, String userId, int page, int pageSize);

    PageResult<ProjectStatisticsResp.AttendRecordRow> attendRecordsForStudent(
            Long projectId, String userId, int page, int pageSize);
}
