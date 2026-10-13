package jack.digityhuman.examservice.dify;

import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Dify 工作流调用实现（blocking）。
 *
 * <p>与 SpeechTraining 的 DifyWorkflowSupport 同款口径：POST {@code /workflows/run}，
 * body = {{inputs, response_mode=blocking, user}}，Bearer 用 app key。
 * 差异：本模块只需要 AI 出题一条链路，故不引入 agentType 路由，直接按配置调用；
 * 失败统一翻译为 50010（03 §10「Dify AI 生成失败」）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyWorkflowSupportImpl implements DifyWorkflowSupport {

    private static final int CODE_AI_GEN_FAILED = 50010;

    private final DifyProperties properties;
    private final ConcurrentMap<Integer, RestTemplate> restTemplates = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> runAiGen(Map<String, Object> inputs) {
        Map<String, Object> body = new HashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", "exam-service");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getAiGenApiKey());

        String url = properties.getBaseUrl() + "/workflows/run";
        log.info("调用 Dify AI出题工作流: url={}, inputs={}", url, inputs.keySet());

        ResponseEntity<Map> response;
        try {
            response = restTemplateFor(properties.getReadTimeoutMs()).exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientException e) {
            log.error("Dify AI出题调用异常: {}", e.getMessage());
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：服务调用异常（超时或不可达）");
        }

        if (response.getBody() == null) {
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：服务无响应");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        if (data == null) {
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：服务响应格式异常");
        }
        Object status = data.get("status");
        if (status != null && !"succeeded".equals(String.valueOf(status))) {
            log.error("Dify AI出题执行失败: status={}, error={}", status, data.get("error"));
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：工作流执行失败");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) data.get("outputs");
        if (outputs == null) {
            throw new BizException(CODE_AI_GEN_FAILED, "AI 生成失败：工作流未返回结果");
        }
        return outputs;
    }

    /** 按读超时缓存 RestTemplate，避免每次请求都新建连接工厂。 */
    RestTemplate restTemplateFor(int configuredReadTimeoutMs) {
        int readTimeoutMs = configuredReadTimeoutMs > 0 ? configuredReadTimeoutMs : 120_000;
        return restTemplates.computeIfAbsent(readTimeoutMs, timeout -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            int connectTimeout = properties.getConnectTimeoutMs() > 0 ? properties.getConnectTimeoutMs() : 10_000;
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(timeout);
            return new RestTemplate(factory);
        });
    }
}