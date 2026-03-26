package org.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Persists session mirror documents off the login request thread so the HTTP response returns faster.
 * Admin session list may lag briefly until this completes.
 */
@Service
public class AuthSessionMirrorService {

    private static final Logger logger = LoggerFactory.getLogger(AuthSessionMirrorService.class);

    private final AuthSessionRepository authSessionRepository;

    public AuthSessionMirrorService(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    @Async
    public void mirrorSessionBestEffort(String token, SessionInfo info) {
        try {
            AuthSessionDocument doc = new AuthSessionDocument();
            doc.setToken(token);
            doc.setExpiresAt(info.expiresAt());
            doc.setDisplayName(info.displayName());
            doc.setUserId(info.userId());
            doc.setAdmin(info.isAdmin());
            doc.setPermissions(info.permissions());
            authSessionRepository.save(doc);
        } catch (Exception e) {
            logger.warn("Could not mirror session to MongoDB (optional). Admin session list may be incomplete. Cause: {}",
                    e.getMessage());
        }
    }
}
