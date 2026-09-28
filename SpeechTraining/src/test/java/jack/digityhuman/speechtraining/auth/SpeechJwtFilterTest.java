package jack.digityhuman.speechtraining.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求级身份上下文测试。
 */
class SpeechJwtFilterTest {

    @AfterEach
    void clearContext() {
        SpeechAuthContext.clear();
    }

    @Test
    void validTokenIsAvailableDuringRequestAndClearedAfterwards() throws Exception {
        SpeechJwtService jwtService = mock(SpeechJwtService.class);
        SpeechJwtFilter filter = new SpeechJwtFilter(jwtService, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/student/projects");
        request.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        SpeechAuthContext.Principal principal =
                new SpeechAuthContext.Principal("1001", "40022966", "周承浩", false);
        when(jwtService.parse("good-token")).thenReturn(Optional.of(principal));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SpeechAuthContext.get()).isNull();
    }

    @Test
    void missingTokenFallsBackToMockInDevelopment() throws Exception {
        SpeechJwtFilter filter = new SpeechJwtFilter(mock(SpeechJwtService.class), true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/student/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertThat(SpeechAuthContext.get()).isNotNull();
            assertThat(SpeechAuthContext.get().account()).isEqualTo("mock_student_001");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingTokenIsRejectedWhenFallbackDisabled() throws Exception {
        SpeechJwtFilter filter = new SpeechJwtFilter(mock(SpeechJwtService.class), false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/student/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getContentAsString()).contains("\"code\":401001");
    }

    @Test
    void malformedTokenDoesNotUseDevelopmentFallback() throws Exception {
        SpeechJwtService jwtService = mock(SpeechJwtService.class);
        SpeechJwtFilter filter = new SpeechJwtFilter(jwtService, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/student/projects");
        request.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(jwtService.parse("bad-token")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getContentAsString()).contains("\"code\":401001");
    }
}
