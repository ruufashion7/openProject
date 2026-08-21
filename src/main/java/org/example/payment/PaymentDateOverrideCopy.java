package org.example.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Safe copies of {@link PaymentDateOverride} so exclusion / retain / active flags are never dropped accidentally.
 */
public final class PaymentDateOverrideCopy {

    private PaymentDateOverrideCopy() {
    }

    public static PaymentDateOverride copy(PaymentDateOverride src) {
        return copy(src, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PaymentDateOverride withActive(PaymentDateOverride src, boolean active) {
        return copy(src, null, null, null, null, null, null, active, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PaymentDateOverride withExcluded(PaymentDateOverride src, boolean excluded, String excludedBy) {
        return new PaymentDateOverride(
                src.id(),
                src.customerKey(),
                src.customerName(),
                src.nextPaymentDate() != null ? src.nextPaymentDate() : "",
                src.phoneNumber(),
                src.whatsAppStatus(),
                src.customerCategory(),
                src.isActive(),
                src.needsFollowUp() != null ? src.needsFollowUp() : false,
                src.address(),
                src.place(),
                src.latitude(),
                src.longitude(),
                src.notes() != null ? src.notes() : List.of(),
                excluded,
                excluded ? Instant.now() : null,
                excluded ? excludedBy : null,
                src.retained(),
                src.retainedAt(),
                src.retainedBy(),
                src.creditLimitOverride(),
                Instant.now()
        );
    }

    public static PaymentDateOverride withRetained(PaymentDateOverride src, boolean retained, String retainedBy) {
        return new PaymentDateOverride(
                src.id(),
                src.customerKey(),
                src.customerName(),
                src.nextPaymentDate() != null ? src.nextPaymentDate() : "",
                src.phoneNumber(),
                src.whatsAppStatus(),
                src.customerCategory(),
                src.isActive(),
                src.needsFollowUp() != null ? src.needsFollowUp() : false,
                src.address(),
                src.place(),
                src.latitude(),
                src.longitude(),
                src.notes() != null ? src.notes() : List.of(),
                src.excluded(),
                src.excludedAt(),
                src.excludedBy(),
                retained,
                retained ? Instant.now() : null,
                retained ? retainedBy : null,
                src.creditLimitOverride(),
                Instant.now()
        );
    }

    public static PaymentDateOverride withNextPaymentDate(PaymentDateOverride src, String nextPaymentDate) {
        return copy(
                src,
                null,
                null,
                nextPaymentDate != null ? nextPaymentDate : "",
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
                null,
                null
        );
    }

    public static PaymentDateOverride withNotes(PaymentDateOverride src, List<CustomerNote> notes) {
        return copy(
                src,
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
                null,
                notes != null ? notes : List.of(),
                null,
                null,
                null
        );
    }

    public static PaymentDateOverride withCreditLimitOverride(PaymentDateOverride src, Double creditLimitOverride) {
        return new PaymentDateOverride(
                src.id(),
                src.customerKey(),
                src.customerName(),
                src.nextPaymentDate() != null ? src.nextPaymentDate() : "",
                src.phoneNumber(),
                src.whatsAppStatus(),
                src.customerCategory(),
                src.isActive(),
                src.needsFollowUp() != null ? src.needsFollowUp() : false,
                src.address(),
                src.place(),
                src.latitude(),
                src.longitude(),
                src.notes() != null ? src.notes() : List.of(),
                src.excluded(),
                src.excludedAt(),
                src.excludedBy(),
                src.retained(),
                src.retainedAt(),
                src.retainedBy(),
                creditLimitOverride,
                Instant.now()
        );
    }

    public static PaymentDateOverride newShell(String customerKey, String customerName) {
        return new PaymentDateOverride(
                null,
                customerKey,
                customerName,
                "",
                null,
                null,
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                false,
                null,
                null,
                null,
                Instant.now()
        );
    }

    @SuppressWarnings("java:S107")
    public static PaymentDateOverride copy(
            PaymentDateOverride src,
            String customerKey,
            String customerName,
            String nextPaymentDate,
            String phoneNumber,
            String whatsAppStatus,
            String customerCategory,
            Boolean active,
            Boolean needsFollowUp,
            String address,
            String place,
            Double latitude,
            Double longitude,
            List<CustomerNote> notes,
            Boolean excluded,
            Instant excludedAt,
            String excludedBy
    ) {
        return copy(
                src,
                customerKey,
                customerName,
                nextPaymentDate,
                phoneNumber,
                whatsAppStatus,
                customerCategory,
                active,
                needsFollowUp,
                address,
                place,
                latitude,
                longitude,
                notes,
                excluded,
                excludedAt,
                excludedBy,
                null,
                null,
                null
        );
    }

    @SuppressWarnings("java:S107")
    public static PaymentDateOverride copy(
            PaymentDateOverride src,
            String customerKey,
            String customerName,
            String nextPaymentDate,
            String phoneNumber,
            String whatsAppStatus,
            String customerCategory,
            Boolean active,
            Boolean needsFollowUp,
            String address,
            String place,
            Double latitude,
            Double longitude,
            List<CustomerNote> notes,
            Boolean excluded,
            Instant excludedAt,
            String excludedBy,
            Boolean retained,
            Instant retainedAt,
            String retainedBy
    ) {
        return new PaymentDateOverride(
                src.id(),
                customerKey != null ? customerKey : src.customerKey(),
                customerName != null ? customerName : src.customerName(),
                nextPaymentDate != null ? nextPaymentDate : (src.nextPaymentDate() != null ? src.nextPaymentDate() : ""),
                phoneNumber != null ? phoneNumber : src.phoneNumber(),
                whatsAppStatus != null ? whatsAppStatus : src.whatsAppStatus(),
                customerCategory != null ? customerCategory : src.customerCategory(),
                active != null ? active : src.isActive(),
                needsFollowUp != null ? needsFollowUp : (src.needsFollowUp() != null ? src.needsFollowUp() : false),
                address != null ? address : src.address(),
                place != null ? place : src.place(),
                latitude != null ? latitude : src.latitude(),
                longitude != null ? longitude : src.longitude(),
                notes != null ? notes : (src.notes() != null ? src.notes() : List.of()),
                excluded != null ? excluded : src.excluded(),
                excludedAt != null ? excludedAt : src.excludedAt(),
                excludedBy != null ? excludedBy : src.excludedBy(),
                retained != null ? retained : src.retained(),
                retainedAt != null ? retainedAt : src.retainedAt(),
                retainedBy != null ? retainedBy : src.retainedBy(),
                src.creditLimitOverride(),
                Instant.now()
        );
    }

    /**
     * Persists location fields from a location update. Unlike {@link #copy}, null address/latitude/longitude
     * explicitly clears those fields (needed for delete and partial clears).
     */
    @SuppressWarnings("java:S107")
    public static PaymentDateOverride withLocationUpdate(
            PaymentDateOverride src,
            String customerKey,
            String customerName,
            String nextPaymentDate,
            String phoneNumber,
            String whatsAppStatus,
            String customerCategory,
            boolean active,
            Boolean needsFollowUp,
            String address,
            String place,
            Double latitude,
            Double longitude,
            List<CustomerNote> notes
    ) {
        return new PaymentDateOverride(
                src.id(),
                customerKey,
                customerName,
                nextPaymentDate != null ? nextPaymentDate : "",
                phoneNumber,
                whatsAppStatus,
                customerCategory,
                active,
                needsFollowUp != null ? needsFollowUp : false,
                address,
                place,
                latitude,
                longitude,
                notes != null ? notes : List.of(),
                src.excluded(),
                src.excludedAt(),
                src.excludedBy(),
                src.retained(),
                src.retainedAt(),
                src.retainedBy(),
                src.creditLimitOverride(),
                Instant.now()
        );
    }

    public static PaymentDateOverride rekey(
            PaymentDateOverride src,
            String newKey,
            String displayName,
            String phoneNumber
    ) {
        return copy(
                src,
                newKey,
                displayName,
                null,
                phoneNumber,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                src.notes() != null ? new ArrayList<>(src.notes()) : List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
