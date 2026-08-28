package org.example.drive;

import org.example.customer.CustomerIdentity;
import org.example.payment.DriveSheetCustomer;
import org.example.payment.OutstandingDueCustomerResolver;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.example.payment.PaymentDateOverrideRepository;
import org.example.payment.PaymentDateRules;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrivePaymentDateSyncServiceTest {

    @Mock
    private PaymentDateOverrideRepository paymentDateOverrideRepository;
    @Mock
    private OutstandingDueCustomerResolver outstandingDueCustomerResolver;
    @Mock
    private DrivePaymentDateSyncStateRepository syncStateRepository;

    private DrivePaymentDateSyncService newService(DriveSyncProperties properties, DriveWorkbookSource source) {
        return new DrivePaymentDateSyncService(
                properties, source, paymentDateOverrideRepository, outstandingDueCustomerResolver, syncStateRepository);
    }

    private static DriveSheetCustomer driveEntry(PaymentDateOverride customer, double amount) {
        return new DriveSheetCustomer(customer, amount);
    }

    @Test
    void status_notConfigured_whenDisabled() {
        DriveSyncProperties properties = new DriveSyncProperties(false, "", "", "", true, 50);
        DrivePaymentDateSyncService service = newService(properties,
                new DriveWorkbookSource() {
                    @Override
                    public DriveWorkbookSnapshot download() {
                        throw new IllegalStateException("should not download");
                    }

                    @Override
                    public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                        throw new UnsupportedOperationException("not needed");
                    }
                });
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
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenAnswer(invocation -> List.of(driveEntry(store.get(0), 1000)));
        when(paymentDateOverrideRepository.findAll()).thenAnswer(invocation -> List.copyOf(store));

        DrivePaymentDateSyncService service = newService(properties, source);

        DrivePaymentDateSyncResponse result = service.syncNow();
        assertEquals("pushed", result.lastStatus());
        assertEquals(1, result.updated());
        assertEquals(1, result.unmatched());

        ArgumentCaptor<PaymentDateOverride> captor = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(paymentDateOverrideRepository).save(captor.capture());
        assertEquals(PaymentDateRules.normalizeOverdueToToday("18-08"), captor.getValue().nextPaymentDate());
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

        DrivePaymentDateSyncService service = newService(properties, source);

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
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenReturn(List.of(driveEntry(existing, 20000)));
        when(paymentDateOverrideRepository.findAll()).thenReturn(List.of(existing));

        DrivePaymentDateSyncService service = newService(properties, source);

        service.syncTwoWayOnLogin();

        assertEquals(2, downloads.get());
        assertEquals(1, uploads.get());
        verify(paymentDateOverrideRepository, never()).save(any());
    }

    @Test
    void syncNow_appendsDriveNoteWhenTextIsNew() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        byte[] xlsx = notesWorkbookBytes("18-08", "Call Monday");
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
                null, null, "01-01", null, null, null, null, null, null, null, null, null, List.of(), null, null, null
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
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenAnswer(invocation -> List.of(driveEntry(store.get(0), 1000)));
        when(paymentDateOverrideRepository.findAll()).thenAnswer(invocation -> List.copyOf(store));

        DrivePaymentDateSyncService service = newService(properties, source);
        service.syncNow();

        ArgumentCaptor<PaymentDateOverride> captor = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(paymentDateOverrideRepository).save(captor.capture());
        PaymentDateOverride saved = captor.getValue();
        assertEquals(PaymentDateRules.normalizeOverdueToToday("18-08"), saved.nextPaymentDate());
        assertEquals(1, saved.notes().size());
        assertEquals("Call Monday", saved.notes().getFirst().note());
        assertEquals("Google Drive", saved.notes().getFirst().createdBy());
    }

    @Test
    void syncNow_skipsDriveNoteWhenAlreadyPresent() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        String today = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd-MM"));
        byte[] xlsx = notesWorkbookBytes(today, "Call Monday");
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
                null, null, today, null, null, null, null, null, null, null, null, null,
                List.of(org.example.payment.CustomerNotes.newDriveNote("Call Monday")),
                null, null, null
        );
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.empty());
        when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentDateOverrideRepository.findAll()).thenReturn(List.of(existing));
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenReturn(List.of(driveEntry(existing, 20000)));
        when(paymentDateOverrideRepository.findAll()).thenReturn(List.of(existing));

        DrivePaymentDateSyncService service = newService(properties, source);
        service.syncNow();

        verify(paymentDateOverrideRepository, never()).save(any());
    }

    @Test
    void syncNow_capsNotesAtSixFromDrive() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        byte[] xlsx = notesWorkbookBytes("18-08", "seventh");
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

        java.time.Instant start = java.time.Instant.parse("2026-01-01T00:00:00Z");
        List<org.example.payment.CustomerNote> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new org.example.payment.CustomerNote(
                    "id-" + i, "note " + i, "staff", start.plusSeconds(i), start.plusSeconds(i), "staff"));
        }
        PaymentDateOverride existing = PaymentDateOverrideCopy.copy(
                PaymentDateOverrideCopy.newShell(CustomerIdentity.normalizeKey("ABC Traders"), "ABC Traders"),
                null, null, "18-08", null, null, null, null, null, null, null, null, null, six, null, null, null
        );
        List<PaymentDateOverride> store = new ArrayList<>();
        store.add(existing);
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.empty());
        when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentDateOverrideRepository.findAll()).thenReturn(store);
        when(paymentDateOverrideRepository.save(any())).thenAnswer(invocation -> {
            PaymentDateOverride saved = invocation.getArgument(0);
            store.set(0, saved);
            return saved;
        });
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenAnswer(invocation -> List.of(driveEntry(store.get(0), 1000)));
        when(paymentDateOverrideRepository.findAll()).thenAnswer(invocation -> List.copyOf(store));

        DrivePaymentDateSyncService service = newService(properties, source);
        service.syncNow();

        ArgumentCaptor<PaymentDateOverride> captor = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(paymentDateOverrideRepository).save(captor.capture());
        PaymentDateOverride saved = captor.getValue();
        assertEquals(6, saved.notes().size());
        assertTrue(saved.notes().stream().anyMatch(note -> "seventh".equals(note.note())));
        assertTrue(saved.notes().stream().noneMatch(note -> "note 0".equals(note.note())));
    }

    @Test
    void syncNow_importsPhoneFromDrive() throws Exception {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        byte[] xlsx = phoneWorkbookBytes("9876543210");
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
                null, null, "18-08", null, null, null, null, null, null, null, null, null, null, null, null, null
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
        when(outstandingDueCustomerResolver.customersForDriveSheet()).thenAnswer(invocation -> List.of(driveEntry(store.get(0), 1000)));
        when(paymentDateOverrideRepository.findAll()).thenAnswer(invocation -> List.copyOf(store));

        DrivePaymentDateSyncService service = newService(properties, source);
        service.syncNow();

        ArgumentCaptor<PaymentDateOverride> captor = ArgumentCaptor.forClass(PaymentDateOverride.class);
        verify(paymentDateOverrideRepository).save(captor.capture());
        assertEquals("919876543210", captor.getValue().phoneNumber());
    }

    @Test
    void syncNow_doesNotPushWhenPullFails() {
        DriveSyncProperties properties = new DriveSyncProperties(
                true, "file-id", "{}", "", true, 50);
        java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();
        DriveWorkbookSource source = new DriveWorkbookSource() {
            @Override
            public DriveWorkbookSnapshot download() {
                throw new RuntimeException("Drive unavailable");
            }

            @Override
            public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) {
                uploads.incrementAndGet();
                return new DriveWorkbookSnapshot("dates.xlsx", "xlsx", "rev-2", written.bytes());
            }
        };
        when(syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)).thenReturn(Optional.empty());
        when(syncStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DrivePaymentDateSyncService service = newService(properties, source);

        DrivePaymentDateSyncResponse result = service.syncNow();
        assertEquals("failed", result.lastStatus());
        assertTrue(result.lastMessage().contains("Drive unavailable"));
        assertEquals(0, uploads.get());
        verify(outstandingDueCustomerResolver, never()).customersForDriveSheet();
    }

    private static byte[] phoneWorkbookBytes(String phone) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Dates");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Phone Number");
            header.createCell(2).setCellValue("Next Payment Date");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue(phone);
            row.createCell(2).setCellValue("18-08");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] notesWorkbookBytes(String date, String note) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Dates");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Customer Name");
            header.createCell(1).setCellValue("Next Payment Date");
            header.createCell(2).setCellValue("Notes");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("ABC Traders");
            row.createCell(1).setCellValue(date);
            row.createCell(2).setCellValue(note);
            workbook.write(out);
            return out.toByteArray();
        }
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
