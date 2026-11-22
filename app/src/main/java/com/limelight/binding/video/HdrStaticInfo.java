package com.limelight.binding.video;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts the host's HDR metadata into the MediaFormat KEY_HDR_STATIC_INFO blob.
 *
 * <p>The host's SS_HDR_METADATA is thirteen little-endian 16-bit values: the display primaries
 * (R, G, B as x/y pairs), the white point, the maximum and minimum mastering luminance, MaxCLL,
 * MaxFALL and the maximum full-frame luminance. MediaCodec takes the first twelve in the same
 * order after a one-byte metadata type, as the Static Metadata Descriptor of a CTA-861.3 Dynamic
 * Range and Mastering InfoFrame; the full-frame luminance has no field there. Stateless; any
 * thread.</p>
 */
final class HdrStaticInfo {
    /** One type byte followed by twelve 16-bit values. */
    static final int SIZE = 25;
    private static final int VALUE_COUNT = 12;

    private HdrStaticInfo() {}

    /** Returns a rewound little-endian buffer of {@link #SIZE} bytes. */
    static ByteBuffer pack(byte[] hostMetadata) {
        ByteBuffer hdrStaticInfo = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer hdrMetadata = ByteBuffer.wrap(hostMetadata).order(ByteOrder.LITTLE_ENDIAN);

        // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
        hdrStaticInfo.put((byte) 0); // Metadata type
        // RX, RY, GX, GY, BX, BY, white X, white Y, max and min mastering luminance,
        // max content luminance, max frame average luminance
        for (int i = 0; i < VALUE_COUNT; i++) {
            hdrStaticInfo.putShort(hdrMetadata.getShort());
        }

        hdrStaticInfo.rewind();
        return hdrStaticInfo;
    }
}
