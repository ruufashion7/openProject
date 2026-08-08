package org.example.settings;

import org.example.payment.PaymentDateOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerCreditLimitServiceTest {

    private CustomerCreditLimitRepository repository;
    private CustomerCreditLimitService service;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerCreditLimitRepository.class);
        service = new CustomerCreditLimitService(repository);
        CustomerCreditLimitDocument doc = new CustomerCreditLimitDocument();
        doc.setLimits(Map.of(
                CustomerCreditLimitService.CATEGORY_A, 50000.0,
                CustomerCreditLimitService.CATEGORY_B, 30000.0,
                CustomerCreditLimitService.CATEGORY_C, 0.0,
                CustomerCreditLimitService.CATEGORY_SEMI_WHOLESALE, 200000.0
        ));
        when(repository.findById(CustomerCreditLimitDocument.DOCUMENT_ID)).thenReturn(java.util.Optional.of(doc));
    }

    @Test
    void resolve_usesOverrideWhenSet() {
        PaymentDateOverride override = shell("A", 75000.0);
        CreditLimitResolution resolution = service.resolve(60000.0, override);
        assertEquals(75000.0, resolution.effectiveCreditLimit());
        assertEquals("override", resolution.creditLimitSource());
        assertFalse(resolution.overCreditLimit());
    }

    @Test
    void resolve_usesCategoryDefaultWhenNoOverride() {
        PaymentDateOverride override = shell("A", null);
        CreditLimitResolution resolution = service.resolve(60000.0, override);
        assertEquals(50000.0, resolution.effectiveCreditLimit());
        assertEquals("category", resolution.creditLimitSource());
        assertTrue(resolution.overCreditLimit());
    }

    @Test
    void resolve_categoryCZeroFlagsAnyPositiveDue() {
        PaymentDateOverride override = shell("C", null);
        CreditLimitResolution resolution = service.resolve(1.0, override);
        assertEquals(0.0, resolution.effectiveCreditLimit());
        assertTrue(resolution.overCreditLimit());
        assertNull(resolution.creditLimitUtilization());
    }

    @Test
    void resolve_noCategoryMeansNoLimit() {
        PaymentDateOverride override = shell(null, null);
        CreditLimitResolution resolution = service.resolve(10000.0, override);
        assertNull(resolution.effectiveCreditLimit());
        assertFalse(resolution.overCreditLimit());
    }

    private static PaymentDateOverride shell(String category, Double overrideLimit) {
        return new PaymentDateOverride(
                "id",
                "test customer",
                "Test Customer",
                "",
                null,
                null,
                category,
                true,
                false,
                null,
                null,
                null,
                null,
                java.util.List.of(),
                false,
                null,
                null,
                false,
                null,
                null,
                overrideLimit,
                null
        );
    }
}
