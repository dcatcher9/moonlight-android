package com.limelight.ui;

import java.util.function.BooleanSupplier;

/** Pure ordering primitive for a Client SBS decoder-producer crossing. */
final class ClientSbsPresentationRetirement {
    private ClientSbsPresentationRetirement() {
    }

    /** Parks MediaCodec without hiding the last SceneCore buffer retained by the old producer. */
    static boolean parkDecoderRetainingPresentation(BooleanSupplier parkDecoder) {
        return parkDecoder != null && parkDecoder.getAsBoolean();
    }
}
