package org.example.bill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillExtractServiceTest {

    @Test
    void fillMissingBills_insertsBlankRowForGap() {
        BillExtractRow a = row("3630", "600");
        BillExtractRow c = row("3632", "900");
        List<BillExtractRow> filled = BillExtractService.fillMissingBills(List.of(c, a));
        assertEquals(3, filled.size());
        assertEquals("3630", filled.get(0).billNo());
        assertEquals("3631", filled.get(1).billNo());
        assertTrue(filled.get(1).missing());
        assertEquals("", filled.get(1).totalAmount());
        assertEquals("Missing bill", filled.get(1).remark());
        assertEquals("3632", filled.get(2).billNo());
        assertFalse(filled.get(2).missing());
        assertEquals("900", filled.get(2).totalAmount());
    }

    @Test
    void fillMissingBills_keepsUnnumberedAtEnd() {
        BillExtractRow numbered = row("10", "100");
        BillExtractRow unreadable = new BillExtractRow("", "", "", "", "", "", "", "Could not read this photo", false);
        List<BillExtractRow> filled = BillExtractService.fillMissingBills(List.of(unreadable, numbered));
        assertEquals(2, filled.size());
        assertEquals("10", filled.get(0).billNo());
        assertEquals("", filled.get(1).billNo());
        assertEquals("Could not read this photo", filled.get(1).remark());
    }

    @Test
    void parseBillNo_readsDigits() {
        assertEquals(10, BillExtractService.parseBillNo("10"));
        assertEquals(3630, BillExtractService.parseBillNo("3630"));
        assertEquals(3630, BillExtractService.parseBillNo("No. 3630"));
        assertEquals(null, BillExtractService.parseBillNo(""));
        assertEquals(null, BillExtractService.parseBillNo(null));
    }

    private static BillExtractRow row(String billNo, String total) {
        return new BillExtractRow(billNo, total, "", total, "Cash", "M", "", "", false);
    }
}
