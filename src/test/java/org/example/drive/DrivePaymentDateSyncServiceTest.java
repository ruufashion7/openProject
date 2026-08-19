package org.example.drive;

import org.example.customer.CustomerIdentity;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.example.payment.PaymentDateOverrideRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrivePaymentDateSyncServiceTest {

    @Mock
    private PaymentDateOverrideRepository paymentDateOverrideRepository;
    @Mock
    private DrivePaymentDateSyncStateRepository syncStateRepository;

    @Test
    void status_notConfigured_whenDisabled() {
        DriveSyncProperties properties = new DriveSyncProperties(false, "", "", "", true, 50);
        DrivePaymentDateSyncService service = new DrivePaymentDateSyncService(
                properties,
                new DriveWorkbookSource() {
                    @Override
                    public DriveWorkbookSnapshot download() {
                        throw new IllegalStateException("should not download");
                    }

                    @Override
                    public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                        throw new UnsupportedOperationException("not needed");
                    }
                },
                paymentDateOverrideRepository,
                syncStateRepository
        );
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.empty());

        DrivePaymentDateSyncResponse status = service.status();
        assertFalse(status.enabled());
        assertFalse(status.configured());
    }

    @Test
    void syncNow_updatesMatchedDate_skipsUnknown() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true,
                "file-id",
                "{\"type\":\"service_account\"}",
                "",
                true,
                50
        );
        byte[] xlsx = workbookBytes();
        DriveWorkbookSource source = new DriveWorkbookSource() {
            @Override
            public DriveWorkbookSnapshot download() {
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "rev-1", xlsx);
            }

            @Override
            public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "rev-2", written.bytes());
            }
        };

        PaymentDateOverride existing = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell(CustomerIdentity.normalizeKey("ABC Traders"), "ABC Traders"),
                null, null, "01-01", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        List<PaymentDateOverride> store = new ArrayList<>();
        store.add(existing);
        java.util.concurrent.atomic.AtomicReference<DrivePaymentDateSyncState> syncState =
                new java.util.concurrent.atomic.AtomicReference<>();

        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID))
                .thenAnswer(invocation -> Optional.ofNullable(syncState.get()));
        when(syncStateRepository.save(any())).thenAnswer(invocation -> {
            DrivePaymentDateSyncState saved = invocation.getArgument(0);
            syncState.set(saved);
            return saved;
        });
        when(paymentDateOverrideRepository.findAll()).thenReturn(store);
        when(paymentDateOverrideRepository.save(any())).thenAnswer(invocation -> {
            PaymentDateOverride saved = invocation.getArgument(0);
            store.set(0, saved);
            return saved;
        });

        DrivePaymentDateSyncService service = new DrivePaymentDateSyncService(
                properties, source, paymentDateOverrideRepository, syncStateRepository);

        DrivePaymentDateSyncResponse result = service.syncNow();
        assertEquals("pushed", result.lastStatus());
        assertEquals(1, result.updated());
        assertEquals(1, result.unmatched());

        ArgumentCaptor<PaymentDateOverride> captor = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(paymentDateOverrideRepository).save(captor.capture());
        assertEquals("18-08", captor.getValue().nextPaymentDate());
    }

    @Test
    void pollIfDue_skipsWhenChecksumUnchanged() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        DriveWorkbookSource source = new DriveWorkbookSource() {
            @Override
            public DriveWorkbookSnapshot download() {
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "same-rev", new byte[] {1, 2, 3});
            }

            @Override
            public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                throw new UnsupportedOperationException("not needed");
            }
        };
        DrivePaymentDateSyncState previous = new DrivePaymentDateSyncState(
                DrivePaymentDateSyncState.SINGLETON_ID,
                null, null, "success", "ok", "dates.xlsx", "same-rev",
                1, 1, 0, 0, 0, 0, List.of(), List.of(), false
        );
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.of(previous));
        when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DrivePaymentDateSyncService service = new DrivePaymentDateSyncService(
                properties, source, paymentDateOverrideRepository, syncStateRepository);

        DrivePaymentDateSyncResponse result = service.pollIfDue();
        assertEquals("skipped", result.lastStatus());
        verify(paymentDateOverrideRepository, never()).findAll();
    }

    @Test
    void syncTwoWayOnLogin_pullsThenPushes() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        byte[] xlsx = workbookBytes();
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();
        DriveWorkbookSource source = new DriveWorkbookSource() {
            @Override
            public DriveWorkbookSnapshot download() {
                downloads.incrementAndGet();
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "rev-1", xlsx);
            }

            @Override
            public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                uploads.incrementAndGet();
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "rev-2", written.bytes());
            }
        };

        PaymentDateOverride existing = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell(CustomerIdentity.normalizeKey("ABC Traders"), "ABC Traders"),
                null, null, "20-08", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        DrivePaymentDateSyncState previous = new DrivePaymentDateSyncState(
                DrivePaymentDateSyncState.SINGLETON_ID,
                null, null, "success", "ok", "dates.xlsx", "rev-1",
                1, 1, 0, 0, 0, 0, List.of(), List.of(), false
        );
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.of(previous));
        when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentDateOverrideRepository.findAll()).thenReturn(List.of(existing));

        DrivePaymentDateSyncService service = new DrivePaymentDateSyncService(
                properties, source, paymentDateOverrideRepository, syncStateRepository);

        service.syncTwoWayOnLogin();

        assertEquals(2, downloads.get());
        assertEquals(1, uploads.get());
        verify(paymentDateOverrideRepository, never()).save(any());
    }

    private static byte[] workbookBytes() throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Dates");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Next Payment Date");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue("18-08");
            var unknown = sheet.createRow(2);
            unknown.createCell(0).setCellValue("Unknown Party");
            unknown.createCell(1).setCellValue("20-08");
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
