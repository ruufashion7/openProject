package org.example.payment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaymentDateOverrideCopyTest {

    @Test
    void withLocationUpdate_clearsNullableLocationFields() {
        PaymentDateOverride existing = new PaymentDateOverride(
                "id-1",
                "abc traders",
                "ABC Traders",
                "15-03",
                "9876543210",
                "sent",
                "A",
                true,
                false,
                "Old address",
                "Mumbai",
                19.0760,
                72.8777,
                List.of(),
                false,
                null,
                null,
                false,
                null,
                null,
                null
        );

        PaymentDateOverride cleared = PaymentDateOverrideCopy.withLocationUpdate(
                existing,
                existing.customerKey(),
                existing.customerName(),
                existing.nextPaymentDate(),
                existing.phoneNumber(),
                existing.whatsAppStatus(),
                existing.customerCategory(),
                true,
                existing.needsFollowUp(),
                null,
                existing.place(),
                null,
                null,
                existing.notes()
        );

        assertNull(cleared.address());
        assertNull(cleared.latitude());
        assertNull(cleared.longitude());
        assertEquals("Mumbai", cleared.place());
        assertEquals("9876543210", cleared.phoneNumber());
    }

    @Test
    void withLocationUpdate_setsNewLocationFields() {
        PaymentDateOverride existing = PaymentDateOverrideCopy.newShell("abc traders", "ABC Traders");

        PaymentDateOverride updated = PaymentDateOverrideCopy.withLocationUpdate(
                existing,
                existing.customerKey(),
                existing.customerName(),
                "",
                null,
                "not sent",
                null,
                true,
                false,
                "New address",
                null,
                28.6139,
                77.2090,
                List.of()
        );

        assertEquals("New address", updated.address());
        assertEquals(28.6139, updated.latitude());
        assertEquals(77.2090, updated.longitude());
    }
}
