package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.InviteStudentsReq;
import jack.digityhuman.speechtraining.dto.req.ProjectStudentQueryReq;
import jack.digityhuman.speechtraining.dto.resp.ProjectStudentRows;

public interface ProjectStudentService {
    PageResult<ProjectStudentRows.ProjectStudentRow> list(
            Long projectId, ProjectStudentQueryReq req);

    ProjectStudentRows.InviteResult invite(Long projectId, InviteStudentsReq req);

    ProjectStudentRows.RemoveResult remove(Long projectId, InviteStudentsReq req);

    PageResult<ProjectStudentRows.CandidateRow> candidates(
            Long projectId, String deptId, String keyword, int page, int pageSize);
}
