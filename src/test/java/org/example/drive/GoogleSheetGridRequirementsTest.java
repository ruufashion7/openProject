package org.example.drive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoogleSheetGridRequirementsTest {

    @Test
    void requiredColumnCount_usesHighestColumnIndexPlusOne() {
        List<SheetCellUpdate> updates = List.of(
                new SheetCellUpdate(0, 0, "Customer ID"),
                new SheetCellUpdate(1, 5, "note"));
        assertEquals(6, GoogleSheetGridRequirements.requiredColumnCount(updates));
    }

    @Test
    void requiredRowCount_usesHighestRowIndexPlusOne() {
        List<SheetCellUpdate> updates = List.of(
                new SheetCellUpdate(0, 0, "header"),
                new SheetCellUpdate(989, 2, "data"));
        assertEquals(990, GoogleSheetGridRequirements.requiredRowCount(updates));
    }
}
