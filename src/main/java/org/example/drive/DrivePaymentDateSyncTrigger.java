package org.example.drive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs Drive two-way payment-date sync off the login thread so sign-in stays fast.
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
        if (!properties.isConfigured()) {
            return;
        }
        try {
            syncService.syncTwoWayOnLogin();
        } catch (Exception ex) {
            log.warn("Drive two-way sync after login failed: {}", ex.getMessage());
        }
    }
}
