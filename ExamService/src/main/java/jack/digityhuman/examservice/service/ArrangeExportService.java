package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.ArrangeDtos;
import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;

import java.util.Map;

/** 考试导出服务（04 API-18/27/28/29/31/35）：统一用 POI 出 xlsx。 */
public interface ArrangeExportService {

    byte[] exportArrangeList(ArrangeDtos.ListQuery query);

    byte[] exportStudents(String arrangeId, String type);

    byte[] exportSignatures(String arrangeId);

    byte[] exportQuestionStat(String arrangeId, ArrangeStatRespDtos.QuestionQuery query);

    /** API-28 异步导出任务（当前实现：同步生成后落临时文件，返回任务号）。 */
    Map<String, Object> createTask(String arrangeId, String type);

    /** API-29 查询导出任务。 */
    Map<String, Object> getTask(String taskId);

    /** 任务文件流（任务成功后可下载）。 */
    byte[] taskFile(String taskId);
}