package org.example.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServletPathsTest {

    @Test
    void exemptsWelcomeAndUploadHeartbeats() {
        assertTrue(ApiServletPaths.isApiRateLimitExempt("GET", "/api/uploads/status"));
        assertTrue(ApiServletPaths.isApiRateLimitExempt("GET", "/api/session"));
        assertTrue(ApiServletPaths.isApiRateLimitExempt("GET", "/api/upload/state"));
        assertTrue(ApiServletPaths.isApiRateLimitExempt("GET", "/api/upload/jobs/abc"));
        assertTrue(ApiServletPaths.isApiRateLimitExempt("POST", "/api/login"));
    }

    @Test
    void doesNotExemptAnalyticsOrJobCancel() {
        assertFalse(ApiServletPaths.isApiRateLimitExempt("GET", "/api/analytics/summary"));
        assertFalse(ApiServletPaths.isApiRateLimitExempt("POST", "/api/upload/jobs/abc/cancel"));
        assertFalse(ApiServletPaths.isApiRateLimitExempt("GET", "/api/outstanding-due"));
    }
}
