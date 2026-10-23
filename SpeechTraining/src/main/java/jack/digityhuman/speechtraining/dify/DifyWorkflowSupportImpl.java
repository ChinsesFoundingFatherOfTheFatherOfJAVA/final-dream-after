package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.agent.AgentWorkflowProperties;
import jack.digityhuman.speechtraining.agent.NativeWorkflowClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Dify 工作流调用实现：blocking 模式发起 workflows/run，按 agent 配置路由与容错。
 */
@Slf4j
@Component
public class DifyWorkflowSupportImpl implements DifyWorkflowSupport {

    private final DifyProperties difyProperties;
    private final NativeWorkflowClient nativeWorkflowClient;
    private final AgentWorkflowProperties agentWorkflowProperties;
    private final ConcurrentMap<Integer, RestTemplate> restTemplates = new ConcurrentHashMap<>();

    public DifyWorkflowSupportImpl(DifyProperties difyProperties) {
        this(difyProperties, null, new AgentWorkflowProperties());
    }

    @Autowired
    public DifyWorkflowSupportImpl(DifyProperties difyProperties,
                                    NativeWorkflowClient nativeWorkflowClient,
                                    AgentWorkflowProperties agentWorkflowProperties) {
        this.difyProperties = difyProperties;
        this.nativeWorkflowClient = nativeWorkflowClient;
        this.agentWorkflowProperties = agentWorkflowProperties;
    }

    /**
     * 调用Dify工作流
     *
     * @param agentType agent类型（对应difyProperties中的key）
     * @param inputs    输入参数
     * @return 工作流输出
     */
    @Override
    public Map<String, Object> runWorkflow(String agentType, Map<String, Object> inputs) {
        if (agentWorkflowProperties != null && agentWorkflowProperties.usesNative(agentType)) {
            if (nativeWorkflowClient == null) {
                throw new BizException("native workflow 客户端未配置");
            }
            return nativeWorkflowClient.run(taskName(agentType), null, null, inputs,
                    0);
        }
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

        ResponseEntity<Map> response = restTemplateFor(config.getReadTimeoutMs()).exchange(
                url,
                HttpMethod.POST,
                request,
                Map.class
        );

        if (response.getBody() == null) {
            throw new BizException("AI 服务无响应，请稍后重试");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        if (data == null) {
            throw new BizException("AI 服务响应格式异常，请稍后重试");
        }

        // blocking 模式下 data.status 为终态。工作流 failed 时 outputs 缺失是正常返回，
        // 若不在此显式拦截，空 map 会一路回到 judgePass，被误判成"学员未命中要点"而静默撤回每条回答
        Object status = data.get("status");
        if (status != null && !"succeeded".equals(String.valueOf(status))) {
            log.error("Dify工作流执行失败: agentType={}, status={}, error={}", agentType, status, data.get("error"));
            throw new BizException("AI 服务执行失败，请稍后重试");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) data.get("outputs");
        if (outputs == null) {
            log.error("Dify工作流无输出内容: agentType={}, status={}", agentType, status);
            throw new BizException("AI 服务未返回结果，请稍后重试");
        }
        return outputs;
    }

    private String taskName(String agentType) {
        return switch (agentType) {
            case "scoring" -> "speech-score";
            case "report-scoring" -> "report-score";
            default -> agentType;
        };
    }

    /** 按读超时复用请求客户端，避免每个工作流请求都创建连接工厂。 */
    RestTemplate restTemplateFor(int configuredReadTimeoutMs) {
        int readTimeoutMs = configuredReadTimeoutMs > 0 ? configuredReadTimeoutMs : 120_000;
        return restTemplates.computeIfAbsent(readTimeoutMs, timeout -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(timeout);
            return new RestTemplate(factory);
        });
    }
}
