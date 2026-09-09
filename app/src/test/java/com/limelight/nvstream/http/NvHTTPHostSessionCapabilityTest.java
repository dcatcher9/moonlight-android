package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.limelight.nvstream.StreamConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public class NvHTTPHostSessionCapabilityTest {
    private static String response(String body) {
        return "<root status_code=\"200\">" + body + "</root>";
    }

    private static final String CURSOR_SERVER_INFO = response(
            "<appversion>7.1.0.0</appversion>"
                    + "<VirtualDisplayOnlySupported>1</VirtualDisplayOnlySupported>"
                    + "<CursorConfinementSupported>1</CursorConfinementSupported>");

    private static NvHTTP.ServerInfoResponse fetchServerInfo(boolean hasPinnedCertificate,
            String httpsResponse, IOException httpsFailure, List<String> requestedSchemes)
            throws Exception {
        HttpCallScope scope = mock(HttpCallScope.class);
        when(scope.execute(any(Call.class))).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0);
            String scheme = call.request().url().scheme();
            requestedSchemes.add(scheme);
            if ("https".equals(scheme) && httpsFailure != null) {
                throw httpsFailure;
            }
            String xml = "https".equals(scheme) ? httpsResponse : CURSOR_SERVER_INFO;
            return new Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(xml, MediaType.get("application/xml"))).build();
        });
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1", 47989),
                47984, "client", hasPinnedCertificate ? mock(X509Certificate.class) : null,
                mock(LimelightCryptoProvider.class), scope);
        return http.getServerInfoWithProvenance(true);
    }

    private static void assertCursorOptionOmitted(NvHTTP.ServerInfoResponse response)
            throws Exception {
        assertEquals(CURSOR_SERVER_INFO, response.xml);
        assertFalse(response.authenticated);
        boolean supported = NvHTTP.isCursorConfinementSupported(
                response.xml, response.authenticated);
        assertFalse(supported);
        assertEquals("", NvHTTP.cursorConfinementQuery(
                new StreamConfiguration.Builder().setConfineCursor(false).build(), supported));
        boolean virtualDisplayOnlySupported = NvHTTP.isVirtualDisplayOnlySupported(
                response.xml, response.authenticated);
        assertFalse(virtualDisplayOnlySupported);
        assertEquals("", NvHTTP.virtualDisplayOnlyQuery(
                new StreamConfiguration.Builder().setVirtualDisplayOnly(false).build(),
                virtualDisplayOnlySupported));
    }

    @Test
    public void successfulHttpsResponseCanAdvertiseCursorConfinement() throws Exception {
        List<String> schemes = new ArrayList<>();
        NvHTTP.ServerInfoResponse info = fetchServerInfo(true, CURSOR_SERVER_INFO, null, schemes);

        assertEquals(Arrays.asList("https"), schemes);
        assertTrue(info.authenticated);
        assertTrue(NvHTTP.isCursorConfinementSupported(info.xml, info.authenticated));
        assertTrue(NvHTTP.isVirtualDisplayOnlySupported(info.xml, info.authenticated));
    }

    @Test
    public void missingPinnedCertificateCannotAdvertiseCursorConfinement() throws Exception {
        List<String> schemes = new ArrayList<>();
        NvHTTP.ServerInfoResponse info = fetchServerInfo(false, null, null, schemes);

        assertEquals(Arrays.asList("http"), schemes);
        assertCursorOptionOmitted(info);
    }

    @Test
    public void unauthorizedHttpsFallbackCannotAdvertiseCursorConfinement() throws Exception {
        List<String> schemes = new ArrayList<>();
        NvHTTP.ServerInfoResponse info = fetchServerInfo(true,
                "<root status_code=\"401\" status_message=\"Unauthorized\"/>", null, schemes);

        assertEquals(Arrays.asList("https", "http"), schemes);
        assertCursorOptionOmitted(info);
    }

    @Test
    public void certificateMismatchFallbackCannotAdvertiseCursorConfinement() throws Exception {
        List<String> schemes = new ArrayList<>();
        SSLHandshakeException mismatch = new SSLHandshakeException("Certificate mismatch");
        mismatch.initCause(new CertificateException("Certificate mismatch"));
        NvHTTP.ServerInfoResponse info = fetchServerInfo(true, null, mismatch, schemes);

        assertEquals(Arrays.asList("https", "http"), schemes);
        assertCursorOptionOmitted(info);
    }

    @Test
    public void cursorConfinementRequiresItsOwnExplicitCapability() throws Exception {
        assertFalse(NvHTTP.isCursorConfinementSupported(response("<hostsessionid>1</hostsessionid>"), true));
        for (String value : new String[] {"", "0", "true", "2"}) {
            assertFalse(NvHTTP.isCursorConfinementSupported(response(
                    "<CursorConfinementSupported>" + value + "</CursorConfinementSupported>"), true));
        }
        assertTrue(NvHTTP.isCursorConfinementSupported(response(
                "<CursorConfinementSupported>1</CursorConfinementSupported>"), true));
        assertFalse(NvHTTP.isCursorConfinementSupported(response(
                "<CursorConfinementSupported>1</CursorConfinementSupported>"), false));
    }

    @Test
    public void launchAndResumeCursorOptionDefaultsOnAndOmitsUnsupportedHosts() {
        StreamConfiguration enabled = new StreamConfiguration.Builder().build();
        StreamConfiguration disabled = new StreamConfiguration.Builder()
                .setConfineCursor(false).build();
        assertEquals("&confineCursor=1", NvHTTP.cursorConfinementQuery(enabled, true));
        assertEquals("&confineCursor=0", NvHTTP.cursorConfinementQuery(disabled, true));
        assertEquals("", NvHTTP.cursorConfinementQuery(enabled, false));
        assertEquals("", NvHTTP.cursorConfinementQuery(disabled, false));
    }

    @Test
    public void virtualDisplayOnlyRequiresItsOwnExplicitCapability() throws Exception {
        assertFalse(NvHTTP.isVirtualDisplayOnlySupported(
                response("<CursorConfinementSupported>1</CursorConfinementSupported>"), true));
        for (String value : new String[] {"", "0", "true", "2"}) {
            assertFalse(NvHTTP.isVirtualDisplayOnlySupported(response(
                    "<VirtualDisplayOnlySupported>" + value
                            + "</VirtualDisplayOnlySupported>"), true));
        }
        assertTrue(NvHTTP.isVirtualDisplayOnlySupported(response(
                "<VirtualDisplayOnlySupported>1</VirtualDisplayOnlySupported>"), true));
        assertFalse(NvHTTP.isVirtualDisplayOnlySupported(response(
                "<VirtualDisplayOnlySupported>1</VirtualDisplayOnlySupported>"), false));
    }

    @Test
    public void launchAndResumeVirtualDisplayOnlyOptionDefaultsOnAndOmitsUnsupportedHosts() {
        // The generated Virtual Display tile does not set StreamConfiguration.virtualDisplay;
        // host-side session classification decides whether this option applies.
        StreamConfiguration enabled = new StreamConfiguration.Builder().build();
        StreamConfiguration disabled = new StreamConfiguration.Builder()
                .setVirtualDisplayOnly(false).build();
        assertFalse(enabled.getVirtualDisplay());
        assertEquals("&virtualDisplayOnly=1",
                NvHTTP.virtualDisplayOnlyQuery(enabled, true));
        assertEquals("&virtualDisplayOnly=0",
                NvHTTP.virtualDisplayOnlyQuery(disabled, true));
        assertEquals("", NvHTTP.virtualDisplayOnlyQuery(enabled, false));
        assertEquals("", NvHTTP.virtualDisplayOnlyQuery(disabled, false));
    }

    @Test
    public void missingTagIsLegacyTokenlessHost() throws Exception {
        ComputerDetails details = new ComputerDetails();

        NvHTTP.populateHostSessionDetails(details, response("<currentgame>0</currentgame>"));

        assertFalse(details.hostSessionIdSupported);
        assertNull(details.hostSessionId);
    }

    @Test
    public void zeroTokenStillAdvertisesCapability() throws Exception {
        ComputerDetails details = new ComputerDetails();

        NvHTTP.populateHostSessionDetails(details,
                response("<hostsessionid>0</hostsessionid>"));

        assertTrue(details.hostSessionIdSupported);
        assertNull(details.hostSessionId);
    }

    @Test
    public void emptyTagStillAdvertisesCapability() throws Exception {
        assertTrue(NvHTTP.hasXmlTag(response("<hostsessionid/>"), "hostsessionid"));
    }

    @Test
    public void activeTokenIsTrimmedAndCopied() throws Exception {
        ComputerDetails parsed = new ComputerDetails();
        NvHTTP.populateHostSessionDetails(parsed,
                response("<hostsessionid> token-123 </hostsessionid>"));

        ComputerDetails copied = new ComputerDetails(parsed);

        assertTrue(copied.hostSessionIdSupported);
        assertEquals("token-123", copied.hostSessionId);
    }

    @Test
    public void tokenlessLaunchResponseIsAcceptedOnlyForLegacyHost() throws Exception {
        String launch = response("<gamesession>1</gamesession>");

        assertNull(NvHTTP.validateHostSessionResponse(launch, false, false, null));

        try {
            NvHTTP.validateHostSessionResponse(launch, true, false, null);
            fail("Token-capable host response must include a token");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void tokenlessResumeResponseIsAcceptedForLegacyHost() throws Exception {
        String resume = response("<resume>1</resume>");

        assertNull(NvHTTP.validateHostSessionResponse(resume, false, true, null));
    }

    @Test
    public void tokenCapableLaunchAcceptsReturnedToken() throws Exception {
        String launch = response(
                "<gamesession>1</gamesession><hostsessionid>token-123</hostsessionid>");

        assertEquals("token-123",
                NvHTTP.validateHostSessionResponse(launch, true, false, null));
    }

    @Test
    public void tokenCapableResumeRequiresMatchingResponseToken() throws Exception {
        String matching = response("<resume>1</resume><hostsessionid>token-123</hostsessionid>");
        String changed = response("<resume>1</resume><hostsessionid>token-456</hostsessionid>");

        assertEquals("token-123",
                NvHTTP.validateHostSessionResponse(matching, true, true, "token-123"));

        try {
            NvHTTP.validateHostSessionResponse(changed, true, true, "token-123");
            fail("Mismatched token must be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
