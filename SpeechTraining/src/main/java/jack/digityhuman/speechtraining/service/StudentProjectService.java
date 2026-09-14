package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.resp.PracticeRecordResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectCardResp;
import jack.digityhuman.speechtraining.dto.resp.StudentPageData;
import jack.digityhuman.speechtraining.dto.resp.StudentProjectDetailResp;

import java.util.List;

/**
 * 学员端项目服务（FP-18）：列表 / 详情 / 练习记录
 */
public interface StudentProjectService {

    /**
     * 分页查询学员端项目列表（已发布项目 + 学员视角状态/达标计算）
     *
     * @param page        页码（默认 1）
     * @param pageSize    每页数量（默认 20）
     * @param status      状态筛选：not_started/ongoing/expired/finished（默认 ongoing）
     * @param projectType 类型筛选：0=训练 / 1=考核
     * @param keyword     项目名称模糊搜索
     */
    StudentPageData<ProjectCardResp> list(long page, long pageSize, String status, Integer projectType, String keyword);

    /**
     * 项目详情（学员视角全量数据）
     */
    StudentProjectDetailResp detail(Long projectId);

    /**
     * 该学员在此项目的历次练习记录
     */
    List<PracticeRecordResp> records(Long projectId);
}
