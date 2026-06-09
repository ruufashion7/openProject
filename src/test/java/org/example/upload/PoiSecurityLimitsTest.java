package org.example.upload;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PoiSecurityLimitsTest {

    @Test
    void applySetsZipSecureLimits() {
        assertDoesNotThrow(PoiSecurityLimits::apply);
        assertDoesNotThrow(() -> ZipSecureFile.getMaxEntrySize());
    }
}
