package jack.digityhuman.aigentservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalAuthFilterTest {

    @Test
    void rejectsInternalRequestWithoutTheSharedToken() throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter("secret", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response,
                (req, res) -> chainCalled.set(true));

        assertEquals(401, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void acceptsMatchingTokenAndLeavesPublicPathsOpen() throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter("secret", true);
        MockHttpServletRequest internal = new MockHttpServletRequest("POST", "/internal/agent/chat");
        internal.addHeader("X-Internal-Token", "secret");
        MockHttpServletResponse internalResponse = new MockHttpServletResponse();
        AtomicBoolean internalCalled = new AtomicBoolean(false);

        filter.doFilter(internal, internalResponse,
                (req, res) -> internalCalled.set(true));

        assertTrue(internalCalled.get());
        assertEquals(200, internalResponse.getStatus());

        MockHttpServletRequest publicRequest = new MockHttpServletRequest("GET", "/actuator/health");
        AtomicBoolean publicCalled = new AtomicBoolean(false);
        filter.doFilter(publicRequest, new MockHttpServletResponse(),
                (req, res) -> publicCalled.set(true));
        assertTrue(publicCalled.get());
    }

    @Test
    void failsClosedWhenAuthIsEnabledButNoTokenIsConfigured() throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter("", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/internal/agent/task/echo"),
                response, (req, res) -> {
                    throw new AssertionError("chain must not run");
                });

        assertEquals(503, response.getStatus());
    }
}
