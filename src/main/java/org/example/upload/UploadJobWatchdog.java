package org.example.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detects stuck or excessively long async uploads and interrupts / releases the upload lock.
 */
@Component
public class UploadJobWatchdog {
    private static final Logger logger = LoggerFactory.getLogger(UploadJobWatchdog.class);

    private final UploadJobService uploadJobService;

    public UploadJobWatchdog(UploadJobService uploadJobService) {
        this.uploadJobService = uploadJobService;
    }

    @Scheduled(fixedDelayString = "${upload.watchdog.interval-ms:60000}", initialDelayString = "${upload.watchdog.initial-delay-ms:120000}")
    public void tick() {
        try {
            uploadJobService.onWatchdogTick();
        } catch (Exception ex) {
            logger.error("Upload watchdog tick failed.", ex);
        }
    }
}
