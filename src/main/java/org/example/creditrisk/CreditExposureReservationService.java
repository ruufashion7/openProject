package org.example.creditrisk;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

@Service
public class CreditExposureReservationService {

    private static final long TTL_HOURS = 24;

    private final CreditExposureReservationRepository repository;

    public CreditExposureReservationService(CreditExposureReservationRepository repository) {
        this.repository = repository;
    }

    public double activeReserved(String customerKey) {
        Optional<CreditExposureReservationDocument> opt = repository.findByCustomerKey(customerKey);
        if (opt.isEmpty()) {
            return 0;
        }
        CreditExposureReservationDocument doc = prune(opt.get());
        repository.save(doc);
        return doc.getReservedAmount();
    }

    public double reserve(String customerKey, String referenceId, double amount) {
        if (referenceId == null || referenceId.isBlank() || amount <= 0) {
            return activeReserved(customerKey);
        }
        CreditExposureReservationDocument doc = repository.findByCustomerKey(customerKey)
                .orElseGet(() -> {
                    CreditExposureReservationDocument d = new CreditExposureReservationDocument();
                    d.setCustomerKey(customerKey);
                    return d;
                });
        prune(doc);
        boolean replaced = false;
        for (CreditExposureReservationDocument.ReservationEntry e : doc.getEntries()) {
            if (referenceId.equals(e.getReferenceId())) {
                e.setAmount(amount);
                e.setExpiresAt(Instant.now().plusSeconds(TTL_HOURS * 3600));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            doc.getEntries().add(new CreditExposureReservationDocument.ReservationEntry(
                    referenceId, amount, Instant.now().plusSeconds(TTL_HOURS * 3600)));
        }
        recalc(doc);
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
        return doc.getReservedAmount();
    }

    private CreditExposureReservationDocument prune(CreditExposureReservationDocument doc) {
        Instant now = Instant.now();
        List<CreditExposureReservationDocument.ReservationEntry> entries =
                doc.getEntries() != null ? doc.getEntries() : new ArrayList<>();
        Iterator<CreditExposureReservationDocument.ReservationEntry> it = entries.iterator();
        while (it.hasNext()) {
            CreditExposureReservationDocument.ReservationEntry e = it.next();
            if (e.getExpiresAt() == null || e.getExpiresAt().isBefore(now)) {
                it.remove();
            }
        }
        doc.setEntries(entries);
        recalc(doc);
        return doc;
    }

    private static void recalc(CreditExposureReservationDocument doc) {
        double sum = 0;
        for (CreditExposureReservationDocument.ReservationEntry e : doc.getEntries()) {
            sum += e.getAmount();
        }
        doc.setReservedAmount(sum);
    }
}
