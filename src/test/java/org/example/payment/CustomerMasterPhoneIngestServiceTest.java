package org.example.payment;

import org.example.upload.UploadedExcelFile;
import org.example.upload.UploadedExcelSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerMasterPhoneIngestServiceTest {

    @Mock
    private PaymentDateOverrideRepository repository;

    private CustomerMasterPhoneIngestService service;

    @BeforeEach
    void setUp() {
        service = new CustomerMasterPhoneIngestService(repository);
    }

    @Test
    void sync_createsNewMasterRow_whenNoExistingMatch() {
        when(repository.findAll()).thenReturn(new ArrayList<>());
        when(repository.save(any(PaymentDateOverride.class))).thenAnswer(inv -> {
            PaymentDateOverride p = inv.getArgument(0);
            if (p.id() == null) {
                return new PaymentDateOverride(
                        "new-id",
                        p.customerKey(),
                        p.customerName(),
                        p.nextPaymentDate(),
                        p.phoneNumber(),
                        p.whatsAppStatus(),
                        p.customerCategory(),
                        p.active(),
                        p.needsFollowUp(),
                        p.address(),
                        p.place(),
                        p.latitude(),
                        p.longitude(),
                        p.notes(),
                        p.excluded(),
                        p.excludedAt(),
                        p.excludedBy(),
                        p.retained(),
                        p.retainedAt(),
                        p.retainedBy(),
                        p.updatedAt()
                );
            }
            return p;
        });

        UploadedExcelFile file = singleRowFile(
                "Customer",
                "Mobile",
                Map.of("Customer", "Acme Traders", "Mobile", "9876543210")
        );

        service.syncPhonesFromUploadFiles(file, null);

        ArgumentCaptor<PaymentDateOverride> cap = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(repository, atLeastOnce()).save(cap.capture());
        PaymentDateOverride saved = cap.getAllValues().getLast();
        assertEquals("919876543210", saved.phoneNumber());
        assertEquals("acme traders", saved.customerKey());
    }

    @Test
    void sync_rekeys_whenSameCanonicalPhone_newLedgerName() {
        PaymentDateOverride old = new PaymentDateOverride(
                "mongo-1",
                "old name pvt ltd",
                "OLD NAME PVT LTD",
                "",
                "919876543210",
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
                Instant.parse("2025-01-01T00:00:00Z")
        );
        when(repository.findAll()).thenReturn(new ArrayList<>(List.of(old)));
        when(repository.save(any(PaymentDateOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        UploadedExcelFile file = singleRowFile(
                "Customer",
                "Mobile",
                Map.of("Customer", "NEW NAME PVT LTD", "Mobile", "+91 98765 43210")
        );

        service.syncPhonesFromUploadFiles(file, null);

        ArgumentCaptor<PaymentDateOverride> cap = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(repository, atLeastOnce()).save(cap.capture());
        PaymentDateOverride saved = cap.getAllValues().getLast();
        assertEquals("new name pvt ltd", saved.customerKey());
        assertEquals("NEW NAME PVT LTD", saved.customerName());
        assertEquals("mongo-1", saved.id());
        assertEquals("919876543210", saved.phoneNumber());
    }

    private static UploadedExcelFile singleRowFile(
            String customerHeader,
            String phoneHeader,
            Map<String, String> row
    ) {
        List<String> headers = List.of(customerHeader, phoneHeader);
        Map<String, String> rowData = new LinkedHashMap<>();
        rowData.put(customerHeader, row.get(customerHeader));
        rowData.put(phoneHeader, row.get(phoneHeader));
        UploadedExcelSheet sheet = new UploadedExcelSheet("S1", headers, List.of(rowData));
        return new UploadedExcelFile("test.xlsx", List.of(sheet));
    }
}
