package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.ScoreConfigDtos;

import java.util.List;

/** 默认分值配置服务（FP-16，07 §7）：设置默认分值抽屉 + 新题默认分值来源。 */
public interface ScoreConfigService {

    List<ScoreConfigDtos.Item> list();

    /** 全量覆盖保存。 */
    void save(ScoreConfigDtos.SaveReq req);
}