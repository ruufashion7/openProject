package org.example.drive;

import org.example.payment.PaymentDateOverride;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debounces app due-date and note saves into a single Drive workbook upload.
 */
@Service
public class DrivePaymentDatePushTrigger {

    private static final long DEBOUNCE_MS = 2000;

    private final DriveSyncProperties properties;
    private final DrivePaymentDateSyncService syncService;
    private final ConcurrentHashMap<String, PaymentDateOverride> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "drive-payment-date-push");
        thread.setDaemon(true);
        return thread;
    });
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledTask;

    public DrivePaymentDatePushTrigger(DriveSyncProperties properties, DrivePaymentDateSyncService syncService) {
        this.properties = properties;
        this.syncService = syncService;
    }

    public void schedule(PaymentDateOverride saved) {
        if (!properties.writeBackEnabled() || !properties.isConfigured()) {
            return;
        }
        if (saved == null || saved.customerKey() == null || saved.customerKey().isBlank()) {
            return;
        }
        pending.put(saved.customerKey(), saved);
        synchronized (scheduleLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
            }
            scheduledTask = scheduler.schedule(this::flushPending, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPending() {
        synchronized (scheduleLock) {
            scheduledTask = null;
        }
        Map<String, PaymentDateOverride> batch = Map.copyOf(pending);
        pending.clear();
        if (!batch.isEmpty()) {
            syncService.pushToDrive(batch.values());
        }
    }
}
