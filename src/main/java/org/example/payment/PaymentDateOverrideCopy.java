package org.example.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Safe copies of {@link PaymentDateOverride} so exclusion / active flags are never dropped accidentally.
 */
public final class PaymentDateOverrideCopy {

    private PaymentDateOverrideCopy() {
    }

    public static PaymentDateOverride copy(PaymentDateOverride src) {
        return copy(src, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static PaymentDateOverride withActive(PaymentDateOverride src, boolean active) {
        return copy(src, null, null, null, null, null, null, active, null, null, null, null, null, null, null, null, null);
    }

    public static PaymentDateOverride withExcluded(PaymentDateOverride src, boolean excluded, String excludedBy) {
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
                null,
                excluded,
                excluded ? Instant.now() : null,
                excluded ? excludedBy : null
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
                null
        );
    }
}
