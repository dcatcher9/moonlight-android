package com.limelight.nvstream.http;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.StreamGamma;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public class NvHTTPStreamGammaTest {
    @Test public void capabilityMustBeExactAndAuthenticated() throws Exception {
        for (String value : new String[]{"0", "2", "true", "01", ""}) {
            assertFalse(NvHTTP.isStreamGammaV1Supported(info(value), true));
        }
        assertTrue(NvHTTP.isStreamGammaV1Supported(info("1"), true));
        assertFalse(NvHTTP.isStreamGammaV1Supported(info("1"), false));
        assertFalse(NvHTTP.isStreamGammaV1Supported("<root status_code=\"200\"></root>", true));
    }

    @Test public void oldHostsReceiveNoLaunchOrResumeOption() {
        for (StreamGamma gamma : StreamGamma.values()) {
            StreamConfiguration config = new StreamConfiguration.Builder().setStreamGamma(gamma).build();
            assertEquals("", NvHTTP.streamGammaQuery(config, false));
            assertEquals("&streamGamma=" + gamma.wireValue, NvHTTP.streamGammaQuery(config, true));
        }
        assertEquals("&streamGamma=0", NvHTTP.streamGammaQuery(
                new StreamConfiguration.Builder().build(), true));
    }

    private String info(String value) {
        return "<root status_code=\"200\"><StreamGammaV1Supported>" + value +
                "</StreamGammaV1Supported></root>";
    }
}
