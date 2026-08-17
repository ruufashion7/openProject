package org.example.bill;

public record BillExtractRow(
        String billNo,
        String totalAmount,
        String discount,
        String amountAfterDiscount,
        String payment,
        String salesman,
        String time,
        String remark,
        boolean missing
) {
    public static BillExtractRow blank(String billNo, boolean missing) {
        return new BillExtractRow(
                billNo == null ? "" : billNo,
                "",
                "",
                "",
                "",
                "",
                "",
                missing ? "Missing bill" : "",
                missing
        );
    }

    public BillExtractRow withDefaults() {
        return new BillExtractRow(
                nullToEmpty(billNo),
                nullToEmpty(totalAmount),
                nullToEmpty(discount),
                nullToEmpty(amountAfterDiscount),
                nullToEmpty(payment),
                nullToEmpty(salesman),
                nullToEmpty(time),
                nullToEmpty(remark),
                missing
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
