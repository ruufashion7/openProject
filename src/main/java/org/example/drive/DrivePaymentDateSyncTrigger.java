package org.example.drive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs Drive two-way payment-date sync off the request/upload thread so the UI stays fast.
 */
@Service
public class DrivePaymentDateSyncTrigger {

    private static final Logger log = LoggerFactory.getLogger(DrivePaymentDateSyncTrigger.class);

    private final DriveSyncProperties properties;
    private final DrivePaymentDateSyncService syncService;

    public DrivePaymentDateSyncTrigger(DriveSyncProperties properties, DrivePaymentDateSyncService syncService) {
        this.properties = properties;
        this.syncService = syncService;
    }

    @Async
    public void onLogin() {
        runTwoWay("login", syncService::syncTwoWayOnLogin);
    }

    @Async
    public void onUploadComplete() {
        runTwoWay("upload", syncService::syncTwoWayAfterUpload);
    }

    private void runTwoWay(String trigger, java.util.function.Supplier<DrivePaymentDateSyncResponse> sync) {
        if (!properties.enabled() || !properties.isConfigured()) {
            return;
        }
        try {
            DrivePaymentDateSyncResponse result = sync.get();
            if (isFailure(result)) {
                log.warn("Drive two-way sync after {} failed: {}", trigger, result.lastMessage());
            }
        } catch (Exception ex) {
            log.warn("Drive two-way sync after {} failed: {}", trigger, ex.getMessage());
        }
    }

    private static boolean isFailure(DrivePaymentDateSyncResponse result) {
        String status = result.lastStatus();
        return "failed".equals(status) || "push-failed".equals(status);
    }
}
