package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.PointDtos;
import jack.digityhuman.examservice.dto.resp.PointRespDtos;

import java.util.List;

/** 考核点（FP-09，03 §4）。 */
public interface PointService {

    List<PointRespDtos.PointItem> list(String libId);

    void batchSave(PointDtos.BatchReq req);
}