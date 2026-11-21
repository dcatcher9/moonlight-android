package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.limelight.ui.PresentationMode;
import com.limelight.ui.xrcontrols.SessionSettingsModel.Key;

import org.junit.Test;

import java.util.EnumMap;

public final class ModeQualityBaselineTest {
    @Test
    public void clientSbsStartsAt1080pAnd30FpsWithTheGlobalBitrate() {
        assertEquals("1920x1080", ModeQualityBaseline.inheritedValue(
                PresentationMode.CLIENT_SBS_AI, Key.RESOLUTION, "3840x2160"));
        assertEquals("30", ModeQualityBaseline.inheritedValue(
                PresentationMode.CLIENT_SBS_AI, Key.FRAME_RATE, "60"));
        assertEquals(200000, ModeQualityBaseline.inheritedValue(
                PresentationMode.CLIENT_SBS_AI, Key.BITRATE, 200000));
        assertNull(ModeQualityBaseline.ownBaseline(PresentationMode.CLIENT_SBS_AI, Key.BITRATE));
    }

    @Test
    public void everyOtherModeInheritsTheGlobalValue() {
        for (PresentationMode mode : PresentationMode.values()) {
            if (mode == PresentationMode.CLIENT_SBS_AI) {
                continue;
            }
            for (Key key : Key.values()) {
                assertNull(mode + " " + key, ModeQualityBaseline.ownBaseline(mode, key));
                assertEquals("global", ModeQualityBaseline.inheritedValue(mode, key, "global"));
            }
        }
    }

    @Test
    public void applyingBaselinesReplacesOnlyTheModesOwnKeys() {
        EnumMap<Key, Object> client = globals();
        ModeQualityBaseline.applyOwnBaselines(PresentationMode.CLIENT_SBS_AI, client);
        assertEquals("1920x1080", client.get(Key.RESOLUTION));
        assertEquals("30", client.get(Key.FRAME_RATE));
        assertEquals(200000, client.get(Key.BITRATE));
        assertEquals(3, client.size());

        EnumMap<Key, Object> host = globals();
        ModeQualityBaseline.applyOwnBaselines(PresentationMode.HOST_SBS_AI, host);
        assertEquals(globals(), host);
    }

    private static EnumMap<Key, Object> globals() {
        EnumMap<Key, Object> values = new EnumMap<>(Key.class);
        values.put(Key.RESOLUTION, "3840x2160");
        values.put(Key.FRAME_RATE, "60");
        values.put(Key.BITRATE, 200000);
        return values;
    }
}
