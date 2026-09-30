package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.resp.ProjectRankResp;

public interface ProjectRankService {
    ProjectRankResp.Rank rank(Long projectId, String dimension, String keyword);
}
