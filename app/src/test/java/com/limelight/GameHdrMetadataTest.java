package com.limelight;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GameHdrMetadataTest {
    @Test
    public void parsesUnsignedLittleEndianMaxContentLightLevel() {
        byte[] metadata = new byte[26];
        metadata[20] = (byte) 0xE8;
        metadata[21] = 0x03;

        assertEquals(1000, Game.parseMaxContentLightLevel(metadata));
    }

    @Test
    public void missingMaxContentLightLevelIsUnknown() {
        assertEquals(0, Game.parseMaxContentLightLevel(null));
        assertEquals(0, Game.parseMaxContentLightLevel(new byte[21]));
    }
}
