package jack.digityhuman.speechtraining.dify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.BizException;
import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyScoringService {

    private final DifyWorkflowSupport difyWorkflowSupport;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        resp.setHitPoints(parseHitPoints(outputs.get("hit_points")));

        resp.setHitCount(toInt(outputs.get("hit_count")));
        resp.setTotalCount(toInt(outputs.get("total_count")));
        resp.setComment((String) outputs.get("comment"));
        resp.setPassed(Boolean.TRUE.equals(outputs.get("passed")));

        return resp;
    }

    /**
     * 解析 hit_points，兼容 DSL end 节点的三种返回形态：原生数组、JSON 数组字符串、非数组脏数据。
     * <p>
     * 解析不出来时抛业务异常而不是回退成空列表：空列表会被 {@code judgePass} 判成"一个要点都没命中"，
     * 于是模型侧任何抖动都会伪装成"学员答错了"，把每条回答静默撤回。
     * 命中结果本身为空数组（学员确实没命中要点）是合法输出，照常返回。
     */
    private List<String> parseHitPoints(Object raw) {
        if (raw == null) {
            throw new BizException("AI 评分未返回命中要点，请重试");
        }
        List<String> hits = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = item == null ? "" : String.valueOf(item).trim();
                if (!value.isEmpty()) {
                    hits.add(value);
                }
            }
            return hits;
        }
        String text = String.valueOf(raw).trim();
        try {
            List<String> parsed = MAPPER.readValue(text, new TypeReference<List<String>>() {
            });
            for (String value : parsed) {
                if (value != null && !value.isBlank()) {
                    hits.add(value.trim());
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("解析命中要点失败，原始值: {}", text, e);
            throw new BizException("AI 评分结果无法解析，请重试");
        }
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
}
