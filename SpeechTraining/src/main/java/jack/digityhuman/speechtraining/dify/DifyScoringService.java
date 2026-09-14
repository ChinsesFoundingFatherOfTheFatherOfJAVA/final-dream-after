package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyScoringService {

    private final DifyWorkflowSupport difyWorkflowSupport;

    /**
     * 调用Dify评分智能体，评估学员的回答
     */
    public AiScoringResp score(String standardScript, String keyPoints, String passCondition, String userInput) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("standard_script", standardScript);
        inputs.put("key_points", keyPoints);
        inputs.put("pass_condition", passCondition);
        inputs.put("user_input", userInput);

        Map<String, Object> outputs = difyWorkflowSupport.runWorkflow("scoring", inputs);

        AiScoringResp resp = new AiScoringResp();

        // 解析hitPoints
        String hitPointsJson = (String) outputs.get("hit_points");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List hitPoints = mapper.readValue(hitPointsJson, List.class);
            resp.setHitPoints(hitPoints);
        } catch (Exception e) {
            log.warn("解析命中要点失败，使用空列表", e);
            resp.setHitPoints(List.of());
        }

        resp.setHitCount(toInt(outputs.get("hit_count")));
        resp.setTotalCount(toInt(outputs.get("total_count")));
        resp.setComment((String) outputs.get("comment"));
        resp.setPassed(Boolean.TRUE.equals(outputs.get("passed")));

        return resp;
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
