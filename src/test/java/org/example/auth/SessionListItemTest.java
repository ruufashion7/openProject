package org.example.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SessionListItemTest {

    @Test
    void masksLongTokens() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String masked = SessionListItem.maskToken(token);
        assertFalse(masked.contains(token));
        assertEquals(token.substring(0, 6) + "…" + token.substring(token.length() - 4), masked);
    }
}
