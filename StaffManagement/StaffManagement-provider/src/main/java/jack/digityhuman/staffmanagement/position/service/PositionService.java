package jack.digityhuman.staffmanagement.position.service;

import jack.digityhuman.staffmanagement.common.PageVo;
import jack.digityhuman.staffmanagement.position.vo.PositionVo;

import java.util.List;

public interface PositionService {
    PageVo<PositionVo> pagePositions(String keyword, Integer page, Integer size);

    List<String> listJobGrades();
}
