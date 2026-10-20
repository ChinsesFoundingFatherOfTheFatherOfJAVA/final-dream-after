package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.ArrangeSettingDtos;

/** 用户考试偏好设置（04 §9 API-46/47）。 */
public interface ArrangeSettingService {

    ArrangeSettingDtos.ColumnSetting getStudentColumns();

    void saveStudentColumns(ArrangeSettingDtos.ColumnSetting req);
}