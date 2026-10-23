package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.dify.DifyCallLogService;
import jack.digityhuman.atualcombattraining.dify.DifyProperties;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowClient;
import jack.digityhuman.atualcombattraining.dify.DifyWorkflowEventParser;
import org.springframework.stereotype.Component;

import java.util.Map;

/** P2 workflow provider 分流；theme/case 服务只依赖本类，不改业务处理。 */
@Component
public class WorkflowRuntimeRouter {

    private final DifyWorkflowClient dify;
    private final NativeWorkflowClient nativeClient;
    private final DifyProperties difyProperties;
    private final AgentWorkflowProperties providers;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowRuntimeRouter(DifyWorkflowClient dify, NativeWorkflowClient nativeClient,
                                 DifyProperties difyProperties,
                                 AgentWorkflowProperties providers) {
        this.dify = dify;
        this.nativeClient = nativeClient;
        this.difyProperties = difyProperties;
        this.providers = providers;
    }

    public ObjectNode runThemeStream(String sessionId, String traceId,
                                     ObjectNode inputs,
                                     DifyWorkflowEventParser.Listener listener) {
        if (providers.nativeTheme()) {
            return nativeClient.runThemeStream(sessionId, traceId, plain(inputs),
                    difyProperties.getThemeTimeoutMs(), listener);
        }
        return dify.runStreaming(DifyCallLogService.APP_THEME_GEN,
                difyProperties.getThemeGenKey(), sessionId, traceId, inputs,
                difyProperties.getThemeTimeoutMs(), listener);
    }

    public ObjectNode runCase(String sessionId, String traceId, ObjectNode inputs) {
        if (providers.nativeCase()) {
            return nativeClient.blocking("case-gen", sessionId, traceId, plain(inputs),
                    difyProperties.getCaseTimeoutMs());
        }
        return dify.run(DifyCallLogService.APP_CASE_GEN,
                difyProperties.getCaseGenKey(), sessionId, traceId, inputs,
                difyProperties.getCaseTimeoutMs());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> plain(ObjectNode node) {
        return mapper.convertValue(node, Map.class);
    }
}
