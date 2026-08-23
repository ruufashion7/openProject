package org.example.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiRateLimitingFilterTest {

    @Test
    void skipsUploadStatusSessionAndJobPolls() throws Exception {
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        ApiRateLimitingFilter filter = new ApiRateLimitingFilter(rateLimitingService, true);
        FilterChain chain = mock(FilterChain.class);

        for (String path : new String[]{
                "/api/uploads/status",
                "/api/session",
                "/api/upload/state",
                "/api/upload/jobs/job-1",
                "/api/login"
        }) {
            MockHttpServletRequest request = request("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }

        verifyNoInteractions(rateLimitingService);
    }

    @Test
    void stillLimitsCancelJobPost() throws Exception {
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        when(rateLimitingService.isApiRequestAllowed("127.0.0.1")).thenReturn(true);
        ApiRateLimitingFilter filter = new ApiRateLimitingFilter(rateLimitingService, true);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = request("POST", "/api/upload/jobs/job-1/cancel");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(rateLimitingService).isApiRequestAllowed("127.0.0.1");
        verify(chain).doFilter(request, response);
    }

    @Test
    void returns429WithRetryAfterWhenOverLimit() throws Exception {
        RateLimitingService rateLimitingService = mock(RateLimitingService.class);
        when(rateLimitingService.isApiRequestAllowed("10.1.1.1")).thenReturn(false);
        when(rateLimitingService.apiRetryAfterSeconds("10.1.1.1")).thenReturn(42);
        ApiRateLimitingFilter filter = new ApiRateLimitingFilter(rateLimitingService, true);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = request("GET", "/api/analytics/summary");
        request.setRemoteAddr("10.1.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("42", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("Too many requests"));
        verify(chain, never()).doFilter(request, response);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
