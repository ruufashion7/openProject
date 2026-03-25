package org.example.security;

import org.example.auth.SessionInfo;

import java.util.Objects;

/**
 * One validated {@link SessionInfo} per HTTP request when {@link ApiBearerAuthenticationFilter} runs,
 * so {@link org.example.auth.AuthSessionService#validate(String)} does not repeat JWT parse + DB work.
 */
public final class RequestScopedSessionCache {

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<SessionInfo> SESSION = new ThreadLocal<>();

    private RequestScopedSessionCache() {
    }

    private record Holder(String token, SessionInfo session) {
    }

    public static void set(String token, SessionInfo session) {
        if (token != null && session != null) {
            CURRENT.set(new Holder(token, session));
            SESSION.set(session);
        }
    }

    public static SessionInfo getSession() {
        return SESSION.get();
    }

    public static SessionInfo getIfTokenMatches(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Holder h = CURRENT.get();
        if (h == null || !Objects.equals(h.token(), token)) {
            return null;
        }
        return h.session();
    }

    public static void clear() {
        CURRENT.remove();
        SESSION.remove();
    }
}
