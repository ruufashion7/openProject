package org.example.customer;

/**
 * Normalizes phone numbers for storage, matching, and future unique indexes.
 * Uses digit-only canonical form with India country code ({@code 91}) when a 10-digit local mobile is detected.
 */
public final class CustomerPhoneNumbers {

    /** Minimum national significant digits (excluding country code) we accept. */
    public static final int MIN_SIGNIFICANT_DIGITS = 10;

    private CustomerPhoneNumbers() {
    }

    /**
     * Canonical digit string for equality and future unique constraints (e.g. {@code 9198xxxxxx00}).
     * Returns null if the input does not contain enough digits to be a plausible phone.
     */
    public static String normalizeDigitsKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.isEmpty()) {
            return null;
        }
        while (d.startsWith("0") && d.length() > 1) {
            d = d.substring(1);
        }
        if (d.length() == 10) {
            d = "91" + d;
        }
        if (d.length() < MIN_SIGNIFICANT_DIGITS) {
            return null;
        }
        return d;
    }

    /**
     * Value stored in {@code customer_master.phoneNumber} when ingested or normalized from uploads.
     */
    public static String canonicalStorageForm(String raw) {
        return normalizeDigitsKey(raw);
    }

    public static boolean sameCanonicalPhone(String storedOrRawA, String storedOrRawB) {
        if (storedOrRawA == null || storedOrRawB == null) {
            return false;
        }
        String a = normalizeDigitsKey(storedOrRawA);
        String b = normalizeDigitsKey(storedOrRawB);
        return a != null && a.equals(b);
    }

    /**
     * Human-readable phone for Drive Excel cells (10-digit local when stored as Indian {@code 91…}).
     */
    public static String driveExcelText(String storedOrRaw) {
        if (storedOrRaw == null || storedOrRaw.isBlank()) {
            return "";
        }
        String canon = normalizeDigitsKey(storedOrRaw);
        if (canon == null) {
            return storedOrRaw.trim();
        }
        if (canon.startsWith("91") && canon.length() == 12) {
            return canon.substring(2);
        }
        return canon;
    }
}
