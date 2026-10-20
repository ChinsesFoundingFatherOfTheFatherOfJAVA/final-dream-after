package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.ArrangeStudentDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStudentRespDtos;

import java.util.Map;

/** 考试考生管理服务（04 §5 API-19~26/30）。 */
public interface ArrangeStudentService {

    PageResult<ArrangeStudentRespDtos.StudentItem> list(String arrangeId, int current, int limit,
                                                        String orderby, String direction,
                                                        ArrangeStudentDtos.ListQuery query);

    Map<String, Object> candidates(String arrangeId, String deptPath, String keyword, int current, int limit);

    Map<String, Object> add(String arrangeId, ArrangeStudentDtos.AddReq req);

    int batchRemove(String arrangeId, ArrangeStudentDtos.BatchRemoveReq req);

    ArrangeStudentRespDtos.RetakeResp retake(String arrangeId, String studentId);

    ArrangeStudentRespDtos.SyncResp syncPaper(String arrangeId, ArrangeStudentDtos.SyncReq req);

    ArrangeStudentRespDtos.AnalyzeResp analyze(String arrangeId);

    int urge(String arrangeId, ArrangeStudentDtos.UrgeReq req);

    PageResult<ArrangeStudentRespDtos.SignatureItem> signatures(String arrangeId, int current, int limit,
                                                               String keyword);
}