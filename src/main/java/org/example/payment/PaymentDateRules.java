package org.example.payment;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Shared rules for the year-less DD-MM payment dates used by the app.
 */
public final class PaymentDateRules {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd-MM");

    private PaymentDateRules() {
    }

    public static boolean isPast(String value) {
        return isPast(value, LocalDate.now());
    }

    static boolean isPast(String value, LocalDate today) {
        LocalDate date = inCurrentYear(value, today);
        return date != null && date.isBefore(today);
    }

    public static String normalizeOverdueToToday(String value) {
        return normalizeOverdueToToday(value, LocalDate.now());
    }

    static String normalizeOverdueToToday(String value, LocalDate today) {
        if (value == null || value.isBlank() || !isPast(value, today)) {
            return value;
        }
        return today.format(DAY_MONTH);
    }

    private static LocalDate inCurrentYear(String value, LocalDate today) {
        if (value == null || !value.matches("\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            String[] parts = value.split("-");
            return LocalDate.of(today.getYear(), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        } catch (DateTimeException | NumberFormatException ex) {
            return null;
        }
    }
}
