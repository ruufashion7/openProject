package org.example.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveSyncPropertiesTest {

    @Test
    void extractFileId_fromUrlAndBareId() {
        DriveSyncProperties fromUrl = new DriveSyncProperties(
                true,
                "https://drive.google.com/file/d/1AbCDefGhiJK/view?usp=sharing",
                "{}",
                "",
                true,
                50
        );
        assertEquals("1AbCDefGhiJK", fromUrl.extractFileId());
        assertTrue(fromUrl.isConfigured());

        DriveSyncProperties bare = new DriveSyncProperties(true, "1AbCDefGhiJK", "{}", "", true, 50);
        assertEquals("1AbCDefGhiJK", bare.extractFileId());

        DriveSyncProperties off = new DriveSyncProperties(false, "1AbCDefGhiJK", "{}", "", true, 50);
        assertFalse(off.isConfigured());
    }
}
