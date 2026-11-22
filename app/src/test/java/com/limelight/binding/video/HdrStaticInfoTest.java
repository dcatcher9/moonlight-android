package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class HdrStaticInfoTest {
    @Test
    public void packsTheTwelveHostValuesAfterTheMetadataType() {
        ByteBuffer host = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 12; i++) {
            host.putShort((short) (0x0100 * (i + 1) + i));
        }

        ByteBuffer packed = HdrStaticInfo.pack(host.array());

        assertEquals(HdrStaticInfo.SIZE, packed.remaining());
        assertEquals(ByteOrder.LITTLE_ENDIAN, packed.order());
        assertEquals(0, packed.get());
        for (int i = 0; i < 12; i++) {
            assertEquals((short) (0x0100 * (i + 1) + i), packed.getShort());
        }
    }

    @Test
    public void ignoresTrailingHostBytes() {
        // SS_HDR_METADATA ends with a thirteenth value, the maximum full-frame luminance.
        byte[] host = new byte[26];
        host[0] = 0x34;
        host[1] = 0x12;
        host[24] = 0x7f;
        host[25] = 0x7f;

        ByteBuffer packed = HdrStaticInfo.pack(host);

        assertEquals(25, packed.limit());
        assertEquals(0, packed.get(0));
        assertEquals(0x1234, packed.getShort(1));
    }
}
