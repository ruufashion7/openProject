package org.example.customer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerPhoneNumbersTest {

    @Test
    void normalizeDigitsKey_indianTenDigit_adds91() {
        assertEquals("919876543210", CustomerPhoneNumbers.normalizeDigitsKey("98765 43210"));
        assertEquals("919876543210", CustomerPhoneNumbers.normalizeDigitsKey("+91 9876543210"));
    }

    @Test
    void normalizeDigitsKey_alreadyWith91_unchanged() {
        assertEquals("919876543210", CustomerPhoneNumbers.normalizeDigitsKey("919876543210"));
    }

    @Test
    void normalizeDigitsKey_stripsLeadingZeros() {
        assertEquals("919876543210", CustomerPhoneNumbers.normalizeDigitsKey("09876543210"));
    }

    @Test
    void normalizeDigitsKey_tooShort_null() {
        assertNull(CustomerPhoneNumbers.normalizeDigitsKey("12345"));
    }

    @Test
    void sameCanonicalPhone_trueWhenFormatsDiffer() {
        assertTrue(CustomerPhoneNumbers.sameCanonicalPhone("9876543210", "+91-98765 43210"));
    }

    @Test
    void sameCanonicalPhone_falseWhenDifferent() {
        assertFalse(CustomerPhoneNumbers.sameCanonicalPhone("9876543210", "9876543211"));
    }

    @Test
    void driveExcelText_stripsIndianCountryCode() {
        assertEquals("9876543210", CustomerPhoneNumbers.driveExcelText("919876543210"));
        assertEquals("9876543210", CustomerPhoneNumbers.driveExcelText("9876543210"));
    }

    @Test
    void customerIdentity_matchesAnalyticsStyleKey() {
        assertEquals("abc trading co", CustomerIdentity.normalizeKey("ABC  Trading & Co."));
    }
}
