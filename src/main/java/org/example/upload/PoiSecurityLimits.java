package org.example.upload;

import jakarta.annotation.PostConstruct;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Component;

/**
 * Apache POI zip-bomb limits applied at startup and before each workbook parse.
 */
@Component
public class PoiSecurityLimits {

    private static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 5_000;
    private static final double MIN_INFLATE_RATIO = 0.01;

    @PostConstruct
    public void init() {
        apply();
    }

    public static void apply() {
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(MAX_FILE_COUNT);
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
    }
}
