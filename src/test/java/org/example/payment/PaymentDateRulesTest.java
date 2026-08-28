package org.example.payment;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDateRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    void identifiesPastDatesInCurrentYear() {
        assertTrue(PaymentDateRules.isPast("26-08", TODAY));
        assertFalse(PaymentDateRules.isPast("28-08", TODAY));
        assertFalse(PaymentDateRules.isPast("29-08", TODAY));
    }

    @Test
    void normalizesOverdueDatesToToday() {
        assertEquals("28-08", PaymentDateRules.normalizeOverdueToToday("26-08", TODAY));
        assertEquals("28-08", PaymentDateRules.normalizeOverdueToToday("28-08", TODAY));
        assertEquals("29-08", PaymentDateRules.normalizeOverdueToToday("29-08", TODAY));
    }
}
