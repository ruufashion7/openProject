package org.example.drive;

import java.util.List;

/**
 * Minimum Google Sheet grid size needed for a batch of cell updates.
 */
final class GoogleSheetGridRequirements {

    private GoogleSheetGridRequirements() {
    }

    static int requiredRowCount(List<SheetCellUpdate> cellUpdates) {
        if (cellUpdates == null || cellUpdates.isEmpty()) {
            return 1;
        }
        int maxRowIndex = cellUpdates.stream().mapToInt(SheetCellUpdate::rowIndex).max().orElse(0);
        return maxRowIndex + 1;
    }

    static int requiredColumnCount(List<SheetCellUpdate> cellUpdates) {
        if (cellUpdates == null || cellUpdates.isEmpty()) {
            return 1;
        }
        int maxColIndex = cellUpdates.stream().mapToInt(SheetCellUpdate::colIndex).max().orElse(0);
        return maxColIndex + 1;
    }
}
