package org.example.drive;

/**
 * A1 range helpers for Google Sheets API.
 */
final class GoogleSheetsCellRange {

    private GoogleSheetsCellRange() {
    }

    static String a1(String sheetName, int rowIndex, int colIndex) {
        return quoteSheet(sheetName) + "!" + columnLetters(colIndex) + (rowIndex + 1);
    }

    static String quoteSheet(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return "'Sheet1'";
        }
        return "'" + sheetName.replace("'", "''") + "'";
    }

    static String columnLetters(int column) {
        int col = column;
        StringBuilder sb = new StringBuilder();
        while (col >= 0) {
            sb.insert(0, (char) ('A' + col % 26));
            col = col / 26 - 1;
        }
        return sb.toString();
    }
}
