package org.example.creditrisk;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.upload.ExcelUploadHeaderRules;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple Order Excel: Customer (+ optional Order Ref) + Order Amount → decision sheet.
 */
@Service
public class CreditRiskOrderExcelService {

    private final CreditRiskService creditRiskService;

    public CreditRiskOrderExcelService(CreditRiskService creditRiskService) {
        this.creditRiskService = creditRiskService;
    }

    public byte[] evaluateWorkbook(InputStream input, String decidedBy, boolean reserveExposure) throws IOException {
        DataFormatter formatter = new DataFormatter();
        List<OrderRow> rows = new ArrayList<>();
        try (Workbook in = WorkbookFactory.create(input)) {
            Sheet sheet = in.getNumberOfSheets() > 0 ? in.getSheetAt(0) : null;
            if (sheet == null) {
                throw new IllegalArgumentException("Workbook has no sheets");
            }
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("Missing header row");
            }
            int customerCol = -1;
            int amountCol = -1;
            int refCol = -1;
            for (Cell cell : header) {
                String h = formatter.formatCellValue(cell).trim();
                if (h.isEmpty()) {
                    continue;
                }
                int idx = cell.getColumnIndex();
                if (customerCol < 0 && (ExcelUploadHeaderRules.isCustomerHeader(h)
                        || h.equalsIgnoreCase("customer id")
                        || h.equalsIgnoreCase("customer name"))) {
                    customerCol = idx;
                } else if (amountCol < 0 && (isOrderAmountHeader(h))) {
                    amountCol = idx;
                } else if (refCol < 0 && isOrderRefHeader(h)) {
                    refCol = idx;
                }
            }
            if (customerCol < 0 || amountCol < 0) {
                throw new IllegalArgumentException("Need Customer and Order Amount columns");
            }
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String customer = formatter.formatCellValue(row.getCell(customerCol)).trim();
                String amountStr = formatter.formatCellValue(row.getCell(amountCol)).trim();
                if (customer.isEmpty() || amountStr.isEmpty()) {
                    continue;
                }
                double amount = CreditRiskParseSupport.parseAmount(amountStr);
                String ref = refCol >= 0 ? formatter.formatCellValue(row.getCell(refCol)).trim() : "";
                rows.add(new OrderRow(customer, amount, ref.isEmpty() ? null : ref));
            }
        }

        try (Workbook out = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = out.createSheet("Order Decisions");
            Row header = sheet.createRow(0);
            String[] cols = {
                    "Customer", "Order Amount", "Order Ref", "Order Decision", "Payment Decision",
                    "Required Payment", "Risk Category", "Risk Score", "Reasons", "Error"
            };
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            int rowIdx = 1;
            for (OrderRow order : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(order.customer());
                row.createCell(1).setCellValue(order.amount());
                row.createCell(2).setCellValue(order.referenceId() != null ? order.referenceId() : "");
                try {
                    boolean reserve = reserveExposure && order.referenceId() != null;
                    OrderDecisionResult result = creditRiskService.evaluate(
                            order.customer(),
                            order.amount(),
                            order.referenceId(),
                            reserve,
                            decidedBy
                    );
                    row.createCell(3).setCellValue(result.orderDecision());
                    row.createCell(4).setCellValue(result.paymentDecision());
                    row.createCell(5).setCellValue(result.requiredPayment());
                    row.createCell(6).setCellValue(result.riskCategory() != null ? result.riskCategory() : "");
                    row.createCell(7).setCellValue(result.riskScore());
                    row.createCell(8).setCellValue(formatReasons(result.reasons()));
                    row.createCell(9).setCellValue("");
                } catch (RuntimeException ex) {
                    row.createCell(3).setCellValue("");
                    row.createCell(4).setCellValue("");
                    row.createCell(5).setCellValue(0);
                    row.createCell(6).setCellValue("");
                    row.createCell(7).setCellValue(0);
                    row.createCell(8).setCellValue("");
                    row.createCell(9).setCellValue(ex.getMessage() != null ? ex.getMessage() : "error");
                }
            }
            out.write(bos);
            return bos.toByteArray();
        }
    }

    private static boolean isOrderAmountHeader(String header) {
        String n = header.toLowerCase(Locale.ROOT);
        return n.equals("order amount")
                || n.equals("amount")
                || n.equals("order value")
                || n.equals("new order amount")
                || (n.contains("order") && n.contains("amount"));
    }

    private static boolean isOrderRefHeader(String header) {
        String n = header.toLowerCase(Locale.ROOT);
        return n.equals("order ref")
                || n.equals("reference id")
                || n.equals("reference")
                || n.equals("order id")
                || n.equals("oms id");
    }

    private static String formatReasons(List<DecisionReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DecisionReason r : reasons) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(r.code());
        }
        return sb.toString();
    }

    private record OrderRow(String customer, double amount, String referenceId) {
    }
}
