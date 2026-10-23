package jack.digityhuman.aigentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.engine.ReActEngine;
import jack.digityhuman.aigentservice.engine.SessionMemory;
import jack.digityhuman.aigentservice.memory.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AIGentService 共享 JSON mapper；模型和工具 adapter 使用同一个实例。 */
@Configuration
public class AgentRuntimeConfiguration {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public SessionMemory sessionMemory(SessionStore sessionStore) {
        return new SessionMemory(24, sessionStore);
    }

    @Bean
    public ReActEngine reActEngine(
            SessionMemory sessionMemory,
            @Value("${agent.react.max-iterations:80}") int maxIterations) {
        return new ReActEngine(maxIterations, sessionMemory);
    }
}
