package org.example.drive;

/**
 * One cell update for Google Sheets API write-back.
 */
public record SheetCellUpdate(int rowIndex, int colIndex, String value) {
}
