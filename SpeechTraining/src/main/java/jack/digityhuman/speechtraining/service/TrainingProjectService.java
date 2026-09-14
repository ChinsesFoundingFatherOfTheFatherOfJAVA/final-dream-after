package jack.digityhuman.speechtraining.service;

import com.baomidou.mybatisplus.spring.service.IService;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.ProjectQueryParam;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectDetailResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectListResp;
import jack.digityhuman.speechtraining.entity.TrainingProject;

public interface TrainingProjectService extends IService<TrainingProject> {

    PageResult<ProjectListResp> listProjects(ProjectQueryParam param);

    ProjectDetailResp getProjectDetail(Long id);

    Long createProject(ProjectSaveReq req);

    void updateProject(Long id, ProjectSaveReq req);

    void publishProject(Long id);

    void withdrawProject(Long id);

    void deleteProject(Long id);

    FlowDataResp getFlowData(Long id);

    void saveFlow(Long projectId, FlowSaveReq req);
}
