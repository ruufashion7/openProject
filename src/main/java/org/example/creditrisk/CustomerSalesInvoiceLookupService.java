package org.example.creditrisk;

import org.example.customer.CustomerIdentity;
import org.example.upload.DetailedSalesInvoicesUpload;
import org.example.upload.DetailedSalesInvoicesUploadRepository;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.UploadedExcelSheet;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class CustomerSalesInvoiceLookupService {

    public record CustomerInvoiceLine(
            String voucherNo,
            double currentDue,
            double received,
            double lineTotal
    ) {
    }

    private final DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository;

    public CustomerSalesInvoiceLookupService(DetailedSalesInvoicesUploadRepository detailedSalesInvoicesUploadRepository) {
        this.detailedSalesInvoicesUploadRepository = detailedSalesInvoicesUploadRepository;
    }

    public Optional<CustomerInvoiceLine> findForCustomer(String customerKey, String customerName, String voucherNo) {
        if (voucherNo == null || voucherNo.isBlank()) {
            return Optional.empty();
        }
        DetailedSalesInvoicesUpload latest = detailedSalesInvoicesUploadRepository.findTopByOrderByUploadedAtDesc();
        if (latest == null || latest.file() == null) {
            return Optional.empty();
        }
        String key = CustomerIdentity.normalizeKey(customerKey);
        String want = normalizeVoucher(voucherNo);
        for (UploadedExcelSheet sheet : latest.file().sheets()) {
            List<String> customerHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCustomerHeader).toList();
            List<String> voucherHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isVoucherHeader).toList();
            List<String> receivedHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isReceivedHeader).toList();
            List<String> dueHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCurrentDueHeader).toList();
            List<String> statusHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isInvoiceStatusHeader).toList();
            List<String> cnHeaders = sheet.headers().stream().filter(ExcelUploadHeaderRules::isCreditNoteHeader).toList();
            if (customerHeaders.isEmpty() || voucherHeaders.isEmpty()) {
                continue;
            }
            for (Map<String, String> row : sheet.rows()) {
                String customer = CreditRiskParseSupport.firstNonBlank(row, customerHeaders);
                if (customer == null || !rowMatchesCustomer(customer, key, customerName)) {
                    continue;
                }
                if (CreditRiskParseSupport.isVoidOrCancelled(row, statusHeaders)) {
                    continue;
                }
                String voucher = CreditRiskParseSupport.firstNonBlank(row, voucherHeaders);
                if (voucher == null || voucher.isBlank()) {
                    continue;
                }
                if (!voucherMatches(voucher, want, voucherNo)) {
                    continue;
                }
                double creditNote = CreditRiskParseSupport.sumAmounts(row, cnHeaders);
                double received = CreditRiskParseSupport.sumAmounts(row, receivedHeaders);
                double currentDue = CreditRiskParseSupport.sumAmounts(row, dueHeaders);
                double lineTotal = Math.max(0, received + currentDue - creditNote);
                return Optional.of(new CustomerInvoiceLine(voucher.trim(), currentDue, received, lineTotal));
            }
        }
        return Optional.empty();
    }

    static String normalizeVoucher(String voucherNo) {
        return voucherNo.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static boolean voucherMatches(String rowVoucher, String normalizedWant, String rawInput) {
        if (normalizeVoucher(rowVoucher).equals(normalizedWant)) {
            return true;
        }
        return rowVoucher.trim().equalsIgnoreCase(rawInput.trim());
    }

    private static boolean rowMatchesCustomer(String customer, String key, String displayName) {
        String rowKey = CustomerIdentity.normalizeKey(customer);
        if (rowKey.equals(key)) {
            return true;
        }
        return CustomerIdentity.matchesFuzzy(customer, displayName)
                || CustomerIdentity.matchesFuzzy(customer, key);
    }
}
