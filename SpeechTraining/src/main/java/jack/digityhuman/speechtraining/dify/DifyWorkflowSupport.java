package jack.digityhuman.speechtraining.dify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DifyWorkflowSupport {

    private final DifyProperties difyProperties;
    private final RestTemplate restTemplate;

    /**
     * 调用Dify工作流
     *
     * @param agentType agent类型（对应difyProperties中的key）
     * @param inputs    输入参数
     * @return 工作流输出
     */
    public Map<String, Object> runWorkflow(String agentType, Map<String, Object> inputs) {
        DifyProperties.AgentConfig config = difyProperties.getAgentConfig(agentType);

        Map<String, Object> body = new HashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", "backend-service");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        String url = config.getBaseUrl() + "/workflows/run";
        log.info("调用Dify工作流: agentType={}, url={}", agentType, url);

        ResponseEntity<Map> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                Map.class
        );

        if (response.getBody() == null) {
            throw new RuntimeException("Dify工作流返回空响应");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        if (data == null) {
            throw new RuntimeException("Dify工作流响应中缺少data字段");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) data.get("outputs");
        return outputs != null ? outputs : new HashMap<>();
    }
}
