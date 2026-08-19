package org.example.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleSheetsCellRangeTest {

    @Test
    void a1_formatsSheetRowAndColumn() {
        assertEquals("'Payment Dates'!B2", GoogleSheetsCellRange.a1("Payment Dates", 1, 1));
        assertEquals("'Sheet1'!A1", GoogleSheetsCellRange.a1("Sheet1", 0, 0));
        assertEquals("'Bob''s Sheet'!C10", GoogleSheetsCellRange.a1("Bob's Sheet", 9, 2));
    }

    @Test
    void columnLetters_supportsWideSheets() {
        assertEquals("A", GoogleSheetsCellRange.columnLetters(0));
        assertEquals("Z", GoogleSheetsCellRange.columnLetters(25));
        assertEquals("AA", GoogleSheetsCellRange.columnLetters(26));
    }
}
