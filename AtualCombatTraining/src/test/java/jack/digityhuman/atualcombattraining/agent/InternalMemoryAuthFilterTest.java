package jack.digityhuman.atualcombattraining.agent;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalMemoryAuthFilterTest {

    @Test
    void protectsOnlyTheMemoryEndpoint() throws Exception {
        InternalMemoryAuthFilter filter = new InternalMemoryAuthFilter("secret", true);
        MockHttpServletRequest memory = new MockHttpServletRequest("GET", "/api/act/internal/memory/s1");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean(false);

        filter.doFilter(memory, denied, (req, res) -> called.set(true));
        assertEquals(401, denied.getStatus());
        assertFalse(called.get());

        memory.addHeader("X-Internal-Token", "secret");
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(memory, accepted, (req, res) -> called.set(true));
        assertTrue(called.get());
        assertEquals(200, accepted.getStatus());

        MockHttpServletRequest tool = new MockHttpServletRequest(
                "POST", "/api/act/internal/tools/create_topic");
        AtomicBoolean toolCalled = new AtomicBoolean(false);
        filter.doFilter(tool, new MockHttpServletResponse(),
                (req, res) -> toolCalled.set(true));
        assertTrue(toolCalled.get());
    }
}
