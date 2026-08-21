package org.example.drive;

import org.example.payment.CustomerNotes;
import org.example.payment.PaymentDateOverride;
import org.example.payment.PaymentDateOverrideCopy;
import org.example.payment.PaymentDateOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DrivePaymentDateSyncService {

    private static final Logger log = LoggerFactory.getLogger(DrivePaymentDateSyncService.class);

    private final DriveSyncProperties properties;
    private final DriveWorkbookSource workbookSource;
    private final PaymentDateOverrideRepository paymentDateOverrideRepository;
    private final DrivePaymentDateSyncStateRepository syncStateRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DrivePaymentDateSyncService(
            DriveSyncProperties properties,
            DriveWorkbookSource workbookSource,
            PaymentDateOverrideRepository paymentDateOverrideRepository,
            DrivePaymentDateSyncStateRepository syncStateRepository
    ) {
        this.properties = properties;
        this.workbookSource = workbookSource;
        this.paymentDateOverrideRepository = paymentDateOverrideRepository;
        this.syncStateRepository = syncStateRepository;
    }

    public DrivePaymentDateSyncResponse status() {
        DrivePaymentDateSyncState state = currentState();
        return toResponse(state);
    }

    public DrivePaymentDateSyncResponse syncNow() {
        return syncTwoWay(false);
    }

    public DrivePaymentDateSyncResponse pollIfDue() {
        if (!properties.isConfigured()) {
            return status();
        }
        return run(true);
    }

    /**
     * Pull from Drive, then push app due dates back to Excel.
     */
    public void syncTwoWayOnLogin() {
        syncTwoWay(true);
    }

    private DrivePaymentDateSyncResponse syncTwoWay(boolean skipPullIfUnchanged) {
        if (!properties.isConfigured()) {
            return status();
        }
        run(skipPullIfUnchanged);
        if (properties.writeBackEnabled()) {
            pushAllDueDatesFromApp();
        }
        return status();
    }

    private void pushAllDueDatesFromApp() {
        List<PaymentDateOverride> toPush = paymentDateOverrideRepository.findAll().stream()
                .filter(DrivePaymentDateSyncService::hasDateOrNotes)
                .toList();
        pushToDrive(toPush);
    }

    /**
     * Writes app due-date edits back to the Drive .xlsx (debounced from payment-date saves).
     */
    public void pushToDrive(Collection<PaymentDateOverride> customers) {
        if (!properties.enabled() || !properties.isConfigured() || !properties.writeBackEnabled()) {
            return;
        }
        if (customers == null || customers.isEmpty()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Drive push skipped: another Drive sync is running.");
            return;
        }
        Instant started = Instant.now();
        try {
            DriveWorkbookSnapshot snapshot = workbookSource.download();
            PaymentDateWorkbookWriter.Result written = PaymentDateWorkbookWriter.applyUpdates(
                    snapshot.bytes(),
                    properties.sheetName(),
                    customers
            );
            if (written.updatedRows() == 0) {
                if (!written.notFoundCustomers().isEmpty()) {
                    log.warn("Drive push: no Excel row for {}", written.notFoundCustomers());
                }
                return;
            }
            DriveWorkbookSnapshot uploaded = workbookSource.upload(snapshot, written);
            String message = "Pushed " + written.updatedRows() + " row"
                    + (written.updatedRows() == 1 ? "" : "s")
                    + " to " + uploaded.fileName() + ".";
            DrivePaymentDateSyncState previous = currentState();
            DrivePaymentDateSyncState pushed = new DrivePaymentDateSyncState(
                    DrivePaymentDateSyncState.SINGLETON_ID,
                    started,
                    Instant.now(),
                    "pushed",
                    message,
                    uploaded.fileName(),
                    uploaded.checksum(),
                    previous.rowsRead(),
                    written.updatedRows(),
                    previous.unchanged(),
                    previous.unmatched(),
                    previous.invalidDates(),
                    previous.ambiguous(),
                    previous.unmatchedRows(),
                    previous.invalidDateRows(),
                    false
            );
            save(pushed);
            log.info("Drive payment-date push finished: {}", message);
        } catch (Exception ex) {
            log.warn("Drive payment-date push failed: {}", ex.getMessage());
            save(withMessage(currentState(), "push-failed", safeMessage(ex)));
        } finally {
            running.set(false);
        }
    }

    private DrivePaymentDateSyncResponse run(boolean skipIfUnchanged) {
        if (!properties.enabled()) {
            return status();
        }
        if (!properties.isConfigured()) {
            DrivePaymentDateSyncState state = save(withMessage(currentState(), "failed",
                    "Drive sync is enabled but GOOGLE_DRIVE_FILE_ID or GOOGLE_SERVICE_ACCOUNT_JSON is missing."));
            return toResponse(state);
        }
        if (!running.compareAndSet(false, true)) {
            DrivePaymentDateSyncState busy = currentState();
            return toResponse(new DrivePaymentDateSyncState(
                    busy.id(),
                    busy.lastStartedAt(),
                    busy.lastFinishedAt(),
                    busy.lastStatus(),
                    "A Drive sync is already running.",
                    busy.lastFileName(),
                    busy.lastChecksum(),
                    busy.rowsRead(),
                    busy.updated(),
                    busy.unchanged(),
                    busy.unmatched(),
                    busy.invalidDates(),
                    busy.ambiguous(),
                    busy.unmatchedRows(),
                    busy.invalidDateRows(),
                    true
            ));
        }
        Instant started = Instant.now();
        try {
            save(markRunning(currentState(), started));
            DriveWorkbookSnapshot snapshot = workbookSource.download();
            DrivePaymentDateSyncState previous = currentState();
            if (skipIfUnchanged
                    && snapshot.checksum() != null
                    && snapshot.checksum().equals(previous.lastChecksum())
                    && "success".equals(previous.lastStatus())) {
                DrivePaymentDateSyncState skipped = new DrivePaymentDateSyncState(
                        DrivePaymentDateSyncState.SINGLETON_ID,
                        started,
                        Instant.now(),
                        "skipped",
                        "Drive file has not changed since the last successful sync.",
                        snapshot.fileName(),
                        snapshot.checksum(),
                        previous.rowsRead(),
                        previous.updated(),
                        previous.unchanged(),
                        previous.unmatched(),
                        previous.invalidDates(),
                        previous.ambiguous(),
                        previous.unmatchedRows(),
                        previous.invalidDateRows(),
                        false
                );
                return toResponse(save(skipped));
            }

            PaymentDateWorkbookParseResult parsed = PaymentDateWorkbookParser.parse(
                    snapshot.bytes(), properties.sheetName());
            ApplyResult applied = applyRows(parsed);

            String message = "Updated " + applied.updated + " customer"
                    + (applied.updated == 1 ? "" : "s")
                    + " from " + snapshot.fileName()
                    + " (sheet " + parsed.sheetName() + ").";
            DrivePaymentDateSyncState success = new DrivePaymentDateSyncState(
                    DrivePaymentDateSyncState.SINGLETON_ID,
                    started,
                    Instant.now(),
                    "success",
                    message,
                    snapshot.fileName(),
                    snapshot.checksum(),
                    parsed.rows().size(),
                    applied.updated,
                    applied.unchanged,
                    applied.unmatchedRows.size(),
                    parsed.invalidDates().size(),
                    applied.ambiguousRows.size(),
                    cap(merge(applied.unmatchedRows, applied.ambiguousRows)),
                    cap(parsed.invalidDates()),
                    false
            );
            log.info("Drive payment-date sync finished: {}", message);
            return toResponse(save(success));
        } catch (Exception ex) {
            log.warn("Drive payment-date sync failed: {}", ex.getMessage());
            DrivePaymentDateSyncState failed = new DrivePaymentDateSyncState(
                    DrivePaymentDateSyncState.SINGLETON_ID,
                    started,
                    Instant.now(),
                    "failed",
                    safeMessage(ex),
                    null,
                    currentState().lastChecksum(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of(),
                    false
            );
            return toResponse(save(failed));
        } finally {
            running.set(false);
        }
    }

    private ApplyResult applyRows(PaymentDateWorkbookParseResult parsed) {
        List<PaymentDateOverride> all = paymentDateOverrideRepository.findAll();
        Map<String, PaymentDateOverride> byKey = DriveCustomerMatcher.indexByKey(all);

        int updated = 0;
        int unchanged = 0;
        List<DriveSyncRowIssue> unmatchedRows = new ArrayList<>();
        List<DriveSyncRowIssue> ambiguousRows = new ArrayList<>();

        for (PaymentDateWorkbookRow row : parsed.rows()) {
            if (DriveCustomerMatcher.isAmbiguous(row, byKey)
                    && DriveCustomerMatcher.match(row, byKey).isEmpty()) {
                ambiguousRows.add(new DriveSyncRowIssue(row.excelRow(), row.customerName(),
                        "Matched more than one customer — use an exact name"));
                continue;
            }
            Optional<PaymentDateOverride> matched = DriveCustomerMatcher.match(row, byKey);
            if (matched.isEmpty()) {
                unmatchedRows.add(new DriveSyncRowIssue(
                        row.excelRow(),
                        row.customerName(),
                        "No matching customer in Outstanding Due / customer master"));
                continue;
            }
            PaymentDateOverride existing = matched.get();
            String currentDate = existing.nextPaymentDate() == null ? "" : existing.nextPaymentDate().trim();
            boolean dateChanged = !row.nextPaymentDate().isBlank() && !Objects.equals(currentDate, row.nextPaymentDate());
            String driveNote = CustomerNotes.normalizeText(row.note());
            boolean notesChanged = !driveNote.isEmpty() && !CustomerNotes.containsSameText(existing.notes(), driveNote);
            if (!dateChanged && !notesChanged) {
                unchanged++;
                continue;
            }
            PaymentDateOverride next = existing;
            if (dateChanged) {
                next = PaymentDateOverrideCopy.withNextPaymentDate(next, row.nextPaymentDate());
            }
            if (notesChanged) {
                next = PaymentDateOverrideCopy.withNotes(
                        next,
                        CustomerNotes.appendCapped(next.notes(), CustomerNotes.newDriveNote(driveNote)));
            }
            PaymentDateOverride saved = paymentDateOverrideRepository.save(next);
            byKey.put(saved.customerKey(), saved);
            updated++;
        }
        return new ApplyResult(updated, unchanged, unmatchedRows, ambiguousRows);
    }

    private static boolean hasDateOrNotes(PaymentDateOverride override) {
        boolean hasDate = override.nextPaymentDate() != null && !override.nextPaymentDate().trim().isBlank();
        boolean hasNotes = override.notes() != null && !override.notes().isEmpty();
        return hasDate || hasNotes;
    }

    private DrivePaymentDateSyncState currentState() {
        return syncStateRepository.findById(DrivePaymentDateSyncState.SINGLETON_ID)
                .orElseGet(DrivePaymentDateSyncState::idle);
    }

    private DrivePaymentDateSyncState save(DrivePaymentDateSyncState state) {
        return syncStateRepository.save(state);
    }

    private DrivePaymentDateSyncState markRunning(DrivePaymentDateSyncState previous, Instant started) {
        return new DrivePaymentDateSyncState(
                DrivePaymentDateSyncState.SINGLETON_ID,
                started,
                previous.lastFinishedAt(),
                previous.lastStatus(),
                "Syncing from Google Drive…",
                previous.lastFileName(),
                previous.lastChecksum(),
                previous.rowsRead(),
                previous.updated(),
                previous.unchanged(),
                previous.unmatched(),
                previous.invalidDates(),
                previous.ambiguous(),
                previous.unmatchedRows(),
                previous.invalidDateRows(),
                true
        );
    }

    private DrivePaymentDateSyncState withMessage(DrivePaymentDateSyncState previous, String status, String message) {
        return new DrivePaymentDateSyncState(
                previous.id(),
                previous.lastStartedAt(),
                Instant.now(),
                status,
                message,
                previous.lastFileName(),
                previous.lastChecksum(),
                previous.rowsRead(),
                previous.updated(),
                previous.unchanged(),
                previous.unmatched(),
                previous.invalidDates(),
                previous.ambiguous(),
                previous.unmatchedRows(),
                previous.invalidDateRows(),
                false
        );
    }

    private List<DriveSyncRowIssue> cap(List<DriveSyncRowIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        int max = properties.maxUnmatchedStored();
        if (issues.size() <= max) {
            return List.copyOf(issues);
        }
        return List.copyOf(issues.subList(0, max));
    }

    private static List<DriveSyncRowIssue> merge(List<DriveSyncRowIssue> a, List<DriveSyncRowIssue> b) {
        List<DriveSyncRowIssue> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Drive sync failed.";
        }
        return message.length() <= 400 ? message : message.substring(0, 400);
    }

    private DrivePaymentDateSyncResponse toResponse(DrivePaymentDateSyncState state) {
        return new DrivePaymentDateSyncResponse(
                properties.enabled(),
                properties.isConfigured(),
                state.running() || running.get(),
                state.lastStartedAt(),
                state.lastFinishedAt(),
                state.lastStatus(),
                state.lastMessage(),
                state.lastFileName(),
                nz(state.rowsRead()),
                nz(state.updated()),
                nz(state.unchanged()),
                nz(state.unmatched()),
                nz(state.invalidDates()),
                nz(state.ambiguous()),
                state.unmatchedRows() == null ? List.of() : state.unmatchedRows(),
                state.invalidDateRows() == null ? List.of() : state.invalidDateRows()
        );
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private record ApplyResult(
            int updated,
            int unchanged,
            List<DriveSyncRowIssue> unmatchedRows,
            List<DriveSyncRowIssue> ambiguousRows
    ) {
    }
}
