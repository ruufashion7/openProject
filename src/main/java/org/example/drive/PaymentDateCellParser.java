package org.example.drive;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.TextStyle;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Excel next-payment cells to the app's {@code DD-MM} form. Blank input is empty (caller skips).
 */
public final class PaymentDateCellParser {

    private static final Pattern DAY_MONTH = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2}|\\d{4})$");
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.ROOT);

    private static final List<DateTimeFormatter> TEXT_FORMATS = List.of(
            new DateTimeFormatterBuilder().parseStrict().appendPattern("dd-MM-uuuu").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseStrict().appendPattern("d-M-uuuu").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseStrict().appendPattern("dd/MM/uuuu").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            new DateTimeFormatterBuilder().parseStrict().appendPattern("d/M/uuuu").toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    );

    private static final List<DateTimeFormatter> MONTH_NAME_FORMATS = List.of(
            monthNameFormatter("d-MMM-uu"),
            monthNameFormatter("dd-MMM-uu"),
            monthNameFormatter("d-MMM-uuuu"),
            monthNameFormatter("dd-MMM-uuuu"),
            monthNameFormatter("d/MMM/uu"),
            monthNameFormatter("dd/MMM/uu"),
            monthNameFormatter("d/MMM/uuuu"),
            monthNameFormatter("dd/MMM/uuuu")
    );

    private PaymentDateCellParser() {
    }

    public static Optional<String> fromCell(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return Optional.of("");
        }
        if (cell.getCellType() == CellType.NUMERIC || (cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)) {
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime ldt = cell.getLocalDateTimeCellValue();
                if (ldt == null) {
                    return Optional.empty();
                }
                return Optional.of(toDayMonth(ldt.toLocalDate()));
            }
            double numeric = cell.getNumericCellValue();
            if (DateUtil.isValidExcelDate(numeric) && numeric > 200 && numeric < 80000) {
                LocalDateTime ldt = DateUtil.getLocalDateTime(numeric);
                if (ldt != null) {
                    return Optional.of(toDayMonth(ldt.toLocalDate()));
                }
            }
        }
        String text = FORMATTER.formatCellValue(cell).trim();
        return fromText(text);
    }

    public static Optional<String> fromText(String raw) {
        if (raw == null) {
            return Optional.of("");
        }
        String value = raw.trim();
        if (value.isEmpty() || value.equals("-") || value.equalsIgnoreCase("n/a") || value.equalsIgnoreCase("na")) {
            return Optional.of("");
        }
        Matcher iso = ISO.matcher(value);
        if (iso.matches()) {
            return validDayMonth(iso.group(3), iso.group(2));
        }
        Matcher dmy = DAY_MONTH_YEAR.matcher(value);
        if (dmy.matches()) {
            return validDayMonth(dmy.group(1), dmy.group(2));
        }
        Matcher dm = DAY_MONTH.matcher(value);
        if (dm.matches()) {
            return validDayMonth(dm.group(1), dm.group(2));
        }
        for (DateTimeFormatter formatter : TEXT_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(value, formatter);
                return Optional.of(toDayMonth(parsed));
            } catch (DateTimeException ignored) {
                // try next
            }
        }
        for (DateTimeFormatter formatter : MONTH_NAME_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(value, formatter);
                return Optional.of(toDayMonth(parsed));
            } catch (DateTimeException ignored) {
                // try next
            }
        }
        return Optional.empty();
    }

    private static DateTimeFormatter monthNameFormatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .parseStrict()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    public static boolean isValidDayMonth(String value) {
        if (value == null || !value.matches("\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            String[] parts = value.split("-");
            LocalDate.of(Year.now().getValue(), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
            return true;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private static Optional<String> validDayMonth(String dayRaw, String monthRaw) {
        try {
            int day = Integer.parseInt(dayRaw);
            int month = Integer.parseInt(monthRaw);
            LocalDate.of(Year.now().getValue(), month, day);
            return Optional.of(toDayMonth(day, month));
        } catch (DateTimeException | NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String toDayMonth(LocalDate date) {
        return toDayMonth(date.getDayOfMonth(), date.getMonthValue());
    }

    private static String toDayMonth(int day, int month) {
        return String.format("%02d-%02d", day, month);
    }

    /** Excel display form used when writing app dates back to Drive (e.g. {@code 19-Aug-26}). */
    public static String toExcelText(String ddMm) {
        if (ddMm == null || ddMm.isBlank()) {
            return "";
        }
        if (!ddMm.matches("\\d{2}-\\d{2}")) {
            return ddMm.trim();
        }
        try {
            String[] parts = ddMm.split("-");
            int day = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            LocalDate.of(Year.now().getValue(), month, day);
            String monthAbbr = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            int year = Year.now().getValue() % 100;
            return day + "-" + monthAbbr + "-" + String.format("%02d", year);
        } catch (DateTimeException | NumberFormatException ex) {
            return ddMm.trim();
        }
    }
}
