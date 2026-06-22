package org.example.payment;

import org.example.customer.CustomerIdentity;
import org.example.customer.CustomerPhoneNumbers;
import org.example.upload.ExcelUploadHeaderRules;
import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * After each sales + receivable Excel upload, merges phone numbers into {@code customer_master}
 * using the same customer-key rules as analytics. Handles ERP renames (same canonical phone, new
 * ledger name) by re-keying the existing master row, enforces one canonical phone per number per
 * sync where possible, and prepares data for a future unique index on normalized digits.
 */
@Service
public class CustomerMasterPhoneIngestService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerMasterPhoneIngestService.class);

    private final PaymentDateOverrideRepository paymentDateOverrideRepository;

    public CustomerMasterPhoneIngestService(PaymentDateOverrideRepository paymentDateOverrideRepository) {
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
    }

    /**
     * Ingests phones from detailed sales (first) then receivable ageing (second). Later files/rows
     * win on conflicts for the same customer key. Phone columns: customer-specific phone headers
     * preferred, then generic mobile/contact columns (excluding pure customer-name headers).
     */
    public void syncPhonesFromUploadFiles(UploadedExcelFile detailedSales, UploadedExcelFile receivableAgeing) {
        Map<String, IngestCandidate> merged = new java.util.LinkedHashMap<>();
        if (detailedSales != null) {
            ingestFileIntoMap(detailedSales, merged);
        }
        if (receivableAgeing != null) {
            ingestFileIntoMap(receivableAgeing, merged);
        }
        List<PaymentDateOverride> cache = new ArrayList<>(paymentDateOverrideRepository.findAll());
        resolveSamePhoneAcrossDifferentKeys(merged, cache);
        for (Map.Entry<String, IngestCandidate> e : merged.entrySet()) {
            IngestCandidate c = e.getValue();
            if (c.digitsKey() == null) {
                continue;
            }
            applyOne(e.getKey(), c, cache);
        }
    }

    private void ingestFileIntoMap(UploadedExcelFile file, Map<String, IngestCandidate> merged) {
        for (UploadedExcelSheet sheet : file.sheets()) {
            List<String> customerNameHeaders = sheet.headers().stream()
                    .filter(ExcelUploadHeaderRules::isCustomerHeader)
                    .filter(h -> !ExcelUploadHeaderRules.isCustomerPhoneHeader(h))
                    .toList();
            List<String> customerHeaders = customerNameHeaders.isEmpty()
                    ? sheet.headers().stream().filter(ExcelUploadHeaderRules::isCustomerHeader).toList()
                    : customerNameHeaders;
            if (customerHeaders.isEmpty()) {
                continue;
            }
            List<String> customerPhoneHeaders = sheet.headers().stream()
                    .filter(ExcelUploadHeaderRules::isCustomerPhoneHeader)
                    .toList();
            List<String> genericPhoneHeaders = sheet.headers().stream()
                    .filter(h -> ExcelUploadHeaderRules.isPhoneHeader(h)
                            && !ExcelUploadHeaderRules.isCustomerPhoneHeader(h))
                    .toList();

            for (Map<String, String> row : sheet.rows()) {
                Optional<String> customerOpt = firstCustomerValue(row, customerHeaders);
                if (customerOpt.isEmpty()) {
                    continue;
                }
                String displayName = customerOpt.get().trim();
                String key = CustomerIdentity.normalizeKey(displayName);
                if (key.isBlank()) {
                    continue;
                }
                String rawPhone = firstNonBlank(row, customerPhoneHeaders);
                if (rawPhone == null) {
                    rawPhone = firstNonBlank(row, genericPhoneHeaders);
                }
                if (rawPhone == null || rawPhone.isBlank()) {
                    merged.merge(key, new IngestCandidate(displayName, null, null), CustomerMasterPhoneIngestService::mergeCandidate);
                    continue;
                }
                String digitsKey = CustomerPhoneNumbers.normalizeDigitsKey(rawPhone);
                String storage = CustomerPhoneNumbers.canonicalStorageForm(rawPhone);
                if (digitsKey == null || storage == null) {
                    merged.merge(key, new IngestCandidate(displayName, null, null), CustomerMasterPhoneIngestService::mergeCandidate);
                    continue;
                }
                merged.merge(key, new IngestCandidate(displayName, storage, digitsKey), CustomerMasterPhoneIngestService::mergeCandidate);
            }
        }
    }

    private static IngestCandidate mergeCandidate(IngestCandidate prev, IngestCandidate next) {
        String name = !isBlank(next.displayName()) ? next.displayName() : prev.displayName();
        if (next.digitsKey() != null) {
            return new IngestCandidate(name, next.storagePhone(), next.digitsKey());
        }
        if (prev.digitsKey() != null) {
            return new IngestCandidate(name, prev.storagePhone(), prev.digitsKey());
        }
        return new IngestCandidate(name, null, null);
    }

    /**
     * If the same normalized phone appears for multiple customer keys in this upload, pick one owner:
     * prefer the key that already holds this phone in master; otherwise smallest key lexicographically.
     */
    private void resolveSamePhoneAcrossDifferentKeys(Map<String, IngestCandidate> merged, List<PaymentDateOverride> cache) {
        Map<String, List<String>> phoneToKeys = new HashMap<>();
        for (Map.Entry<String, IngestCandidate> e : merged.entrySet()) {
            if (e.getValue().digitsKey() == null) {
                continue;
            }
            phoneToKeys.computeIfAbsent(e.getValue().digitsKey(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : phoneToKeys.entrySet()) {
            List<String> keys = e.getValue();
            if (keys.size() <= 1) {
                continue;
            }
            String digits = e.getKey();
            String winner = null;
            for (String k : keys) {
                Optional<PaymentDateOverride> doc = findFirstByCustomerKey(cache, k);
                if (doc.isPresent() && CustomerPhoneNumbers.sameCanonicalPhone(doc.get().phoneNumber(), digits)) {
                    winner = k;
                    break;
                }
            }
            if (winner == null) {
                winner = Collections.min(keys);
            }
            logger.warn(
                    "Upload maps normalized phone {} to multiple customer keys {}; assigning to {}",
                    digits,
                    keys,
                    winner
            );
            for (String k : keys) {
                if (!k.equals(winner)) {
                    IngestCandidate c = merged.get(k);
                    merged.put(k, c.dropPhone());
                }
            }
        }
    }

    private void applyOne(String customerKey, IngestCandidate c, List<PaymentDateOverride> cache) {
        String digits = c.digitsKey();
        String storage = c.storagePhone();
        String displayName = c.displayName();

        Optional<PaymentDateOverride> forKey = findFirstByCustomerKey(cache, customerKey);
        if (forKey.isPresent()) {
            PaymentDateOverride ex = forKey.get();
            PaymentDateOverride saved = saveWithPhoneAndName(ex, storage, displayName);
            if (!samePhoneAndName(ex, saved)) {
                paymentDateOverrideRepository.save(saved);
                replaceById(cache, saved);
            }
            clearPhoneFromOthers(cache, saved.id(), digits);
            return;
        }

        List<PaymentDateOverride> phoneOwners = findAllWithCanonicalPhone(cache, digits);
        if (phoneOwners.size() > 1) {
            logger.warn(
                    "Skipping phone ingest for customerKey={}: {} customer_master rows already share normalized phone {}",
                    customerKey,
                    phoneOwners.size(),
                    digits
            );
            return;
        }
        if (phoneOwners.size() == 1) {
            PaymentDateOverride old = phoneOwners.getFirst();
            if (!old.customerKey().equals(customerKey)) {
                PaymentDateOverride rekeyed = rekeyPreservingFields(old, customerKey, displayName, storage);
                paymentDateOverrideRepository.save(rekeyed);
                replaceById(cache, rekeyed);
                clearPhoneFromOthers(cache, rekeyed.id(), digits);
            }
            return;
        }

        PaymentDateOverride created = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell(
                        customerKey,
                        !isBlank(displayName) ? displayName : customerKey
                ),
                null,
                null,
                null,
                storage,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        PaymentDateOverride saved = paymentDateOverrideRepository.save(created);
        cache.add(saved);
        clearPhoneFromOthers(cache, saved.id(), digits);
    }

    private static boolean samePhoneAndName(PaymentDateOverride a, PaymentDateOverride b) {
        return Objects.equals(a.phoneNumber(), b.phoneNumber())
                && Objects.equals(a.customerName(), b.customerName());
    }

    private void clearPhoneFromOthers(List<PaymentDateOverride> cache, String keepId, String digitsKey) {
        for (int i = 0; i < cache.size(); i++) {
            PaymentDateOverride p = cache.get(i);
            if (p.id() == null || p.id().equals(keepId)) {
                continue;
            }
            if (!CustomerPhoneNumbers.sameCanonicalPhone(p.phoneNumber(), digitsKey)) {
                continue;
            }
            PaymentDateOverride cleared = stripPhone(p);
            paymentDateOverrideRepository.save(cleared);
            cache.set(i, cleared);
        }
    }

    private static PaymentDateOverride stripPhone(PaymentDateOverride p) {
        return PaymentDateOverrideCopy.copy(
                p,
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static PaymentDateOverride saveWithPhoneAndName(PaymentDateOverride ex, String storagePhone, String displayName) {
        String name = !isBlank(displayName) ? displayName.trim() : ex.customerName();
        return PaymentDateOverrideCopy.copy(
                ex,
                null,
                name,
                null,
                storagePhone,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static PaymentDateOverride rekeyPreservingFields(
            PaymentDateOverride old,
            String newKey,
            String displayName,
            String storagePhone
    ) {
        return PaymentDateOverrideCopy.rekey(old, newKey, displayName, storagePhone);
    }

    private static Optional<PaymentDateOverride> findFirstByCustomerKey(List<PaymentDateOverride> cache, String key) {
        return cache.stream().filter(p -> key.equals(p.customerKey())).findFirst();
    }

    private static List<PaymentDateOverride> findAllWithCanonicalPhone(List<PaymentDateOverride> cache, String digitsKey) {
        return cache.stream()
                .filter(p -> CustomerPhoneNumbers.sameCanonicalPhone(p.phoneNumber(), digitsKey))
                .toList();
    }

    private static void replaceById(List<PaymentDateOverride> cache, PaymentDateOverride saved) {
        for (int i = 0; i < cache.size(); i++) {
            if (saved.id() != null && saved.id().equals(cache.get(i).id())) {
                cache.set(i, saved);
                return;
            }
        }
        cache.add(saved);
    }

    private static Optional<String> firstCustomerValue(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private static String firstNonBlank(Map<String, String> row, List<String> headers) {
        for (String header : headers) {
            String value = row.get(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record IngestCandidate(String displayName, String storagePhone, String digitsKey) {
        IngestCandidate dropPhone() {
            return new IngestCandidate(displayName, null, null);
        }
    }
}
