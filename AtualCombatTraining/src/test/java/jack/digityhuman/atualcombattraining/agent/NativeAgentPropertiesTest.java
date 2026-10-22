package jack.digityhuman.atualcombattraining.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeAgentPropertiesTest {

    @Test
    void bindsTheNativeRuntimeAddressAndTimeoutFromAgentChatNativePrefix() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "agent.chat.native.base-url", "http://native.example:8086",
                "agent.chat.native.connect-timeout-ms", "1234",
                "agent.chat.native.timeout-ms", "5678"));

        NativeAgentProperties properties = new Binder(source)
                .bind("agent.chat.native", Bindable.of(NativeAgentProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertEquals("http://native.example:8086", properties.getBaseUrl());
        assertEquals(1234, properties.getConnectTimeoutMs());
        assertEquals(5678, properties.getTimeoutMs());
    }
}
