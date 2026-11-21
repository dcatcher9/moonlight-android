package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.List;

/** Malformed host responses fail as malformed XML, never as unchecked parser exceptions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public final class NvHTTPMalformedResponseTest {
    @Test public void nonNumericStatusCodeIsMalformedXml() {
        assertThrows(XmlPullParserException.class, () -> NvHTTP.getXmlString(
                "<root status_code=\"oops\"><a>1</a></root>", "a", true));
    }

    @Test public void nonNumericNumericFieldsAreMalformedXml() throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1", 47989), 47984,
                "client", null, mock(LimelightCryptoProvider.class));
        String serverInfo = "<root status_code=\"200\"><state>SUNSHINE_SERVER_BUSY</state>"
                + "<currentgame>seven</currentgame><HttpsPort>port</HttpsPort>"
                + "<ServerCodecModeSupport>many</ServerCodecModeSupport></root>";

        assertThrows(XmlPullParserException.class, () -> http.getCurrentGame(serverInfo));
        assertThrows(XmlPullParserException.class,
                () -> http.getServerCodecModeSupport(serverInfo));
        // The HTTPS port keeps its documented default for a missing or unusable value.
        assertEquals(47984, http.getHttpsPort(serverInfo));
    }

    @Test public void whitespaceBetweenAppElementsIsIgnored() throws Exception {
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(
                "<root status_code=\"200\">\n  <App><AppTitle>Desktop</AppTitle><ID>1</ID></App>\n"
                        + "</root>"));

        assertEquals(1, apps.size());
        assertEquals("Desktop", apps.get(0).getAppName());
    }

    @Test public void nonNumericAppIndexIsMalformedXml() {
        assertThrows(XmlPullParserException.class, () -> NvHTTP.getAppListByReader(
                new StringReader("<root status_code=\"200\"><App><AppTitle>A</AppTitle>"
                        + "<IDX>first</IDX><ID>1</ID></App></root>")));
    }
}
