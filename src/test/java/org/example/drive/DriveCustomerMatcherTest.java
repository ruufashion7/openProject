package org.example.drive;

import org.example.customer.CustomerIdentity;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveCustomerMatcherTest {

    @Test
    void match_exactNormalizedName() {
        PaymentDateOverride abc = customer("ABC Traders", null, "01-01");
        Map<String, PaymentDateOverride> byKey = DriveCustomerMatcher.indexByKey(List.of(abc));
        PaymentDateWorkbookRow row = new PaymentDateWorkbookRow(2, "abc traders", "", "18-08");
        Optional<PaymentDateOverride> match = DriveCustomerMatcher.match(row, byKey, Map.of());
        assertTrue(match.isPresent());
        assertEquals("ABC Traders", match.get().customerName());
    }

    @Test
    void match_phoneWinsOverName() {
        PaymentDateOverride named = customer("Wrong Name", "911111111111", "01-01");
        PaymentDateOverride phoned = customer("Right Name", "9876543210", "02-02");
        List<PaymentDateOverride> all = List.of(named, phoned);
        PaymentDateWorkbookRow row = new PaymentDateWorkbookRow(2, "Wrong Name", "9876543210", "18-08");
        Optional<PaymentDateOverride> match = DriveCustomerMatcher.match(
                row,
                DriveCustomerMatcher.indexByKey(all),
                DriveCustomerMatcher.indexByPhone(all)
        );
        assertTrue(match.isPresent());
        assertEquals("Right Name", match.get().customerName());
    }

    @Test
    void match_unknownName_empty() {
        PaymentDateOverride abc = customer("ABC Traders", null, "01-01");
        PaymentDateWorkbookRow row = new PaymentDateWorkbookRow(2, "Totally Different LLC", "", "18-08");
        Optional<PaymentDateOverride> match = DriveCustomerMatcher.match(
                row,
                DriveCustomerMatcher.indexByKey(List.of(abc)),
                Map.of()
        );
        assertTrue(match.isEmpty());
    }

    private static PaymentDateOverride customer(String name, String phone, String date) {
        PaymentDateOverride shell = PaymentDateOverrideCopy.newShell(CustomerIdentity.normalizeKey(name), name);
        return PaymentDateOverrideCopy.copy(
                shell,
                null,
                null,
                date,
                phone,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
