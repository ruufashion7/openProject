package org.example.creditrisk;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ponytail: single-document per customerKey with TTL expiry; upgrade to distributed lock if multi-node contention.
 */
@Document(collection = "credit_exposure_reservations")
public class CreditExposureReservationDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String customerKey;
    private double reservedAmount;
    private List<ReservationEntry> entries = new ArrayList<>();
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerKey() {
        return customerKey;
    }

    public void setCustomerKey(String customerKey) {
        this.customerKey = customerKey;
    }

    public double getReservedAmount() {
        return reservedAmount;
    }

    public void setReservedAmount(double reservedAmount) {
        this.reservedAmount = reservedAmount;
    }

    public List<ReservationEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ReservationEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public static class ReservationEntry {
        private String referenceId;
        private double amount;
        private Instant expiresAt;

        public ReservationEntry() {
        }

        public ReservationEntry(String referenceId, double amount, Instant expiresAt) {
            this.referenceId = referenceId;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }

        public String getReferenceId() {
            return referenceId;
        }

        public void setReferenceId(String referenceId) {
            this.referenceId = referenceId;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
