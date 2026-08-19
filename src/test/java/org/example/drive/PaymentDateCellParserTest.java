package org.example.drive;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentDateCellParserTest {

    @Test
    void fromText_blank_isEmptyDate() {
        assertEquals(Optional.of(""), PaymentDateCellParser.fromText(""));
        assertEquals(Optional.of(""), PaymentDateCellParser.fromText("  "));
        assertEquals(Optional.of(""), PaymentDateCellParser.fromText("-"));
    }

    @Test
    void fromText_dayMonth_padded() {
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("18-08"));
        assertEquals(Optional.of("05-03"), PaymentDateCellParser.fromText("5/3"));
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("18.08"));
    }

    @Test
    void fromText_withYear_keepsDayMonth() {
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("18-08-2026"));
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("18/08/2026"));
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("2026-08-18"));
    }

    @Test
    void fromText_monthAbbreviation_withTwoDigitYear() {
        assertEquals(Optional.of("19-08"), PaymentDateCellParser.fromText("19-Aug-26"));
        assertEquals(Optional.of("05-03"), PaymentDateCellParser.fromText("5-Mar-2026"));
        assertEquals(Optional.of("18-08"), PaymentDateCellParser.fromText("18/AUG/26"));
    }

    @Test
    void toExcelText_monthAbbrev_withCurrentYear() {
        assertEquals("19-Aug-26", PaymentDateCellParser.toExcelText("19-08"));
        assertEquals("", PaymentDateCellParser.toExcelText(""));
    }

    @Test
    void fromText_invalid_emptyOptional() {
        assertTrue(PaymentDateCellParser.fromText("32-13").isEmpty());
        assertTrue(PaymentDateCellParser.fromText("not-a-date").isEmpty());
        assertTrue(PaymentDateCellParser.fromText("18-00").isEmpty());
    }
}
