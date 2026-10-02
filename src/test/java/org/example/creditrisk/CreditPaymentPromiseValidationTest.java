package org.example.creditrisk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditPaymentPromiseValidationTest {

    @Test
    void requiresInvoiceOrAmount() {
        var lookup = mock(CustomerSalesInvoiceLookupService.class);
        var repo = mock(CreditPaymentPromiseRepository.class);
        when(repo.findByCustomerKeyOrderByPromiseDateDesc(anyString())).thenReturn(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> CreditPaymentPromiseValidation.resolve(
                        lookup, repo, "k", "Acme", "", null, 10, null, "note")
        );
    }

    @Test
    void amountOnlyMustBePositive() {
        var lookup = mock(CustomerSalesInvoiceLookupService.class);
        var repo = mock(CreditPaymentPromiseRepository.class);
        when(repo.findByCustomerKeyOrderByPromiseDateDesc(anyString())).thenReturn(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> CreditPaymentPromiseValidation.resolve(
                        lookup, repo, "k", "Acme", null, 0d, 10, null, "note")
        );
    }

    @Test
    void invoiceNotFound() {
        var lookup = mock(CustomerSalesInvoiceLookupService.class);
        when(lookup.findForCustomer(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var repo = mock(CreditPaymentPromiseRepository.class);
        when(repo.findByCustomerKeyOrderByPromiseDateDesc(anyString())).thenReturn(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> CreditPaymentPromiseValidation.resolve(
                        lookup, repo, "k", "Acme", "INV-1", null, 10, null, "note")
        );
    }

    @Test
    void invoiceOnlyUsesCurrentDue() {
        var lookup = mock(CustomerSalesInvoiceLookupService.class);
        when(lookup.findForCustomer("k", "Acme", "INV-1"))
                .thenReturn(Optional.of(new CustomerSalesInvoiceLookupService.CustomerInvoiceLine("INV-1", 5000, 0, 5000)));
        var repo = mock(CreditPaymentPromiseRepository.class);
        when(repo.findByCustomerKeyOrderByPromiseDateDesc(anyString())).thenReturn(List.of());
        var resolved = CreditPaymentPromiseValidation.resolve(
                lookup, repo, "k", "Acme", "INV-1", null, 10, null, "shirt");
        assertEquals(5000, resolved.promiseAmount(), 0.01);
        assertEquals("INV-1", resolved.voucherNo());
    }

    @Test
    void amountCannotExceedInvoiceDue() {
        var lookup = mock(CustomerSalesInvoiceLookupService.class);
        when(lookup.findForCustomer("k", "Acme", "INV-1"))
                .thenReturn(Optional.of(new CustomerSalesInvoiceLookupService.CustomerInvoiceLine("INV-1", 1000, 0, 1000)));
        var repo = mock(CreditPaymentPromiseRepository.class);
        when(repo.findByCustomerKeyOrderByPromiseDateDesc(anyString())).thenReturn(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> CreditPaymentPromiseValidation.resolve(
                        lookup, repo, "k", "Acme", "INV-1", 2000d, 10, null, "note")
        );
    }
}
